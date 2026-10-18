package com.yunyin.music.data.download

import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.net.NeteaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads single tracks, with a small queue.
 *
 * ## Resolution is playback's resolution
 *
 * URLs come from a resolver injected by the container (`MusicRepository.streamUrl`) — the same call the player uses —
 * so an enabled custom source or ported provider is consulted first and the NetEase route is the fallback. That also
 * means the member-preview behaviour is the playback behaviour, which is why the preview gate lives here.
 *
 * ## Enqueueing is fire-and-forget on the manager's own scope
 *
 * [download] and [cancel] are plain calls: every job is launched on the manager's injected scope (owned by the
 * application container), never on a screen's `rememberCoroutineScope`. A sheet that closes immediately after the tap
 * therefore cannot cancel the download — the bug class the user's "nothing happened" report hinted at. [download]
 * also emits a notice before any suspension, so a tap always says something.
 *
 * ## Failure discipline
 *
 * Every entry point returns normally; anything thrown inside a job becomes a [DownloadPhase.FAILED] state with a
 * specific message, a user-visible notice and a `stage=download` record. A failed state is retryable.
 */
class TrackDownloadManager(
    private val resolve: suspend (trackId: Long, quality: String) -> NeteaseClient.SongUrl?,
    private val preferredQuality: () -> String,
    private val sink: DownloadSink,
    private val log: LxSourceLog,
    private val scope: CoroutineScope,
    private val probe: suspend (url: String, trackDurationMs: Long) -> Pair<DownloadProbeVerdict, Long?> =
        { _, _ -> DownloadProbeVerdict.UNKNOWN to null },
    private val maxConcurrent: Int = 2,
) {

    private val lock = Any()
    private val tracks = ConcurrentHashMap<Long, Track>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val waiting = ArrayDeque<Long>()
    private var active = 0

    private val _states = MutableStateFlow<Map<Long, TrackDownloadState>>(emptyMap())
    val states: StateFlow<Map<Long, TrackDownloadState>> = _states.asStateFlow()

    /** App-wide transient messages for the download action ("正在下载…", "已保存到…", "下载失败：…"). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun stateFor(trackId: Long): TrackDownloadState = _states.value[trackId] ?: TrackDownloadState(trackId)

    /**
     * Enqueues a download and returns whether it was accepted (false for an already-running or already-downloaded
     * track). Never suspends and never throws: the actual work starts on the manager's scope.
     */
    fun download(track: Track): Boolean {
        val quality = preferredQuality()
        synchronized(lock) {
            val current = stateFor(track.id)
            if (current.phase == DownloadPhase.DONE || current.isActive) {
                notice(
                    when (current.phase) {
                        DownloadPhase.DONE -> "「${track.name}」已下载过"
                        else -> "「${track.name}」正在下载中"
                    },
                )
                return false
            }
            tracks[track.id] = track
            // Emit before any suspension: a tap must always produce feedback, even if resolution later fails.
            notice("正在下载「${track.name}」（${DownloadQualityPlan.qualityLabel(quality)}）")
            logDownload(track, "layer=enqueue quality=$quality")
            if (active >= maxConcurrent) {
                waiting.addLast(track.id)
                update(TrackDownloadState(track.id, DownloadPhase.WAITING))
                notice("已加入下载队列：「${track.name}」")
                logDownload(track, "layer=queued position=$active active=$active")
            } else {
                active++
                start(track, quality)
            }
            return true
        }
    }

    /** Cancels a running or waiting download. A running one aborts on the next chunk and deletes its pending row. */
    fun cancel(trackId: Long) {
        synchronized(lock) {
            if (waiting.remove(trackId)) {
                update(TrackDownloadState(trackId, DownloadPhase.IDLE))
                notice("已取消下载")
                log(trackId, "layer=cancel state=waiting")
                return
            }
            val job = jobs[trackId] ?: return
            // Update the label immediately: the writer may still be flushing/deleting the pending row.
            update(TrackDownloadState(trackId, DownloadPhase.FAILED, message = "已取消下载"))
            notice("已取消下载")
            log(trackId, "layer=cancel state=running")
            job.cancel()
        }
    }

    /** Drops a remembered state; UI-facing only, does not delete files. */
    fun forget(trackId: Long) {
        _states.value = _states.value - trackId
    }

    private fun start(track: Track, quality: String) {
        val job = scope.launch {
            try {
                runDownload(track, quality)
            } catch (e: CancellationException) {
                update(TrackDownloadState(track.id, DownloadPhase.FAILED, message = "已取消下载"))
                logDownload(track, "result=cancelled")
                throw e
            } catch (t: Throwable) {
                // Belt and braces: runDownload already catches, but a future edit must not be able to kill the app.
                val message = "下载失败：${short(t)}"
                update(TrackDownloadState(track.id, DownloadPhase.FAILED, message = message))
                notice(message)
                logDownload(track, "result=crash error=${short(t)}")
            } finally {
                jobs.remove(track.id)
                finish(track.id)
            }
        }
        jobs[track.id] = job
    }

    private fun finish(trackId: Long) {
        synchronized(lock) {
            active = (active - 1).coerceAtLeast(0)
            val next = waiting.removeFirstOrNull() ?: return
            val track = tracks[next] ?: return
            active++
            start(track, preferredQuality())
        }
    }

    private suspend fun runDownload(track: Track, preferredQuality: String) {
        update(TrackDownloadState(track.id, DownloadPhase.RESOLVING))
        val order = DownloadQualityPlan.order(preferredQuality)
        var failure: String? = null
        for (quality in order) {
            val resolved = try {
                resolve(track.id, quality)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logDownload(track, "layer=resolve quality=$quality error=${short(t)}")
                continue
            }
            val url = resolved?.url?.takeIf { it.isNotBlank() }
            if (url == null) {
                logDownload(track, "layer=resolve quality=$quality result=none")
                failure = "音源未返回地址"
                continue
            }
            if (resolved.isTrial) {
                logDownload(track, "layer=gate quality=$quality result=trial")
                failure = DownloadPreviewGate.MESSAGE
                continue
            }
            val (verdict, length) = try {
                probe(url, track.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logDownload(track, "layer=probe quality=$quality error=${short(t)}")
                DownloadProbeVerdict.UNKNOWN to null
            }
            logDownload(track, "layer=probe quality=$quality verdict=$verdict length=${length ?: -1}")
            if (DownloadPreviewGate.refuse(verdict, length, track.durationMs)) {
                logDownload(track, "layer=gate quality=$quality result=preview refusal=refused")
                failure = DownloadPreviewGate.MESSAGE
                continue
            }
            update(TrackDownloadState(track.id, DownloadPhase.DOWNLOADING, progress = 0f))
            var lastBucket = -1L
            try {
                val saved = sink.save(track, url) { downloaded, total ->
                    val bucket = if (total != null && total > 0) {
                        (downloaded * 100 / total).coerceIn(0L, 100L)
                    } else {
                        downloaded / (256 * 1024)
                    }
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        val progress = if (total != null && total > 0) bucket / 100f else null
                        update(TrackDownloadState(track.id, DownloadPhase.DOWNLOADING, progress = progress))
                    }
                }
                update(
                    TrackDownloadState(
                        track.id,
                        DownloadPhase.DONE,
                        progress = 1f,
                        fileName = saved.displayName,
                        uri = saved.audioUri,
                    ),
                )
                notice("已保存到 ${saved.folder}：${saved.displayName}")
                logDownload(track, "result=done quality=$quality folder=${saved.folder} file=${saved.displayName} uri=${saved.audioUri}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logDownload(track, "layer=write quality=$quality error=${t.javaClass.simpleName}: ${short(t)}")
                failure = storageErrorMessage(t)
                continue
            }
        }
        update(TrackDownloadState(track.id, DownloadPhase.FAILED, message = failure ?: "下载失败"))
        if (failure == DownloadPreviewGate.MESSAGE) {
            notice(DownloadPreviewGate.MESSAGE)
        } else {
            notice("下载失败：${failure ?: "原因未知"}")
        }
        logDownload(track, "result=failed reason=${failure ?: "unknown"}")
    }

    /** IOException during the write is either storage (space / permission / MediaStore) or the network stream. */
    private fun storageErrorMessage(t: Throwable): String {
        val text = generateSequence(t) { it.cause }.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
        return when {
            text.contains("ENOSPC", true) || text.contains("No space left", true) ->
                "空间不足，下载已取消"
            t is MediaStoreAudioWriter.HttpStatusException -> "地址已失效（HTTP ${t.code}）"
            t is MediaStoreAudioWriter.UnknownFormatException -> "存储写入失败：无法识别音频格式"
            t is MediaStoreAudioWriter.NotAudioException -> t.message ?: "返回的不是音频"
            t is IOException && (text.contains("Permission", true) || text.contains("EACCES", true)) ->
                "存储写入失败：没有写入共享存储的权限"
            text.contains("MediaStore", true) || text.contains("存储", true) || text.contains("insert", true) ->
                "存储写入失败：${short(t)}"
            else -> "网络错误：${short(t)}"
        }
    }

    private fun update(state: TrackDownloadState) {
        _states.value = _states.value + (state.trackId to state)
    }

    private fun notice(message: String) {
        runCatching { _notices.tryEmit(message) }
    }

    private fun log(trackId: Long, detail: String) {
        runCatching { log.record("下载", "stage=download track=$trackId $detail") }
    }

    private fun logDownload(track: Track, detail: String) {
        runCatching {
            log.record(
                "下载",
                "stage=download track=${track.id} name=${track.name} artist=${track.artistLine} $detail",
            )
        }
    }

    private fun short(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull().orEmpty().take(160)
}
