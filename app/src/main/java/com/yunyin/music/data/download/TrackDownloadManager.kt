// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

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
 * Downloads tracks, with a small queue plus pause/resume and persistence.
 *
 * URLs come from the same resolver the player uses (`MusicRepository.streamUrl`), so the download follows the active
 * quality/source rules. At most [maxConcurrent] downloads run at once; records loaded from [records] keep the download
 * centre populated across a restart, and the sink's Range requests make an interrupted file resumable.
 */
class TrackDownloadManager(
    private val resolve: suspend (trackId: Long, quality: String) -> NeteaseClient.SongUrl?,
    private val preferredQuality: () -> String,
    private val sink: DownloadSink,
    private val log: LxSourceLog,
    private val scope: CoroutineScope,
    private val probe: suspend (url: String, trackDurationMs: Long) -> Pair<DownloadProbeVerdict, Long?> =
        { _, _ -> DownloadProbeVerdict.UNKNOWN to null },
    private val maxConcurrent: Int = DownloadSchedulerRules.MAX_CONCURRENT,
    private val records: DownloadRecordStore? = null,
    private val wifiOnly: () -> Boolean = { false },
    private val onWifi: () -> Boolean = { true },
    private val availableBytes: () -> Long = { Long.MAX_VALUE },
    private val deleteStored: (String) -> Boolean = { true },
) {

    private val lock = Any()
    private val tracks = ConcurrentHashMap<Long, Track>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val waiting = ArrayDeque<Long>()
    private val pausedIds = ConcurrentHashMap.newKeySet<Long>()
    private val deletedIds = ConcurrentHashMap.newKeySet<Long>()
    private var active = 0

    private val _states = MutableStateFlow<Map<Long, TrackDownloadState>>(emptyMap())
    val states: StateFlow<Map<Long, TrackDownloadState>> = _states.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Invoked after a file lands in MediaStore, so the local library can re-index it without a page visit. */
    var onCompleted: ((com.yunyin.music.core.Track) -> Unit)? = null

    init {
        records?.all()?.forEach { record ->
            tracks[record.track.id] = record.track
            val phase = when (record.phase) {
                DownloadPhase.DONE -> DownloadPhase.DONE
                DownloadPhase.FAILED -> DownloadPhase.FAILED
                else -> DownloadPhase.PAUSED
            }
            _states.value = _states.value + (record.track.id to TrackDownloadState(
                trackId = record.track.id,
                phase = phase,
                progress = record.totalBytes?.let { DownloadResumeRules.progress(record.downloadedBytes, it) },
                message = record.message,
                fileName = record.fileName,
                uri = record.uri,
                downloadedBytes = record.downloadedBytes,
                totalBytes = record.totalBytes,
            ))
        }
    }

    fun stateFor(trackId: Long): TrackDownloadState = _states.value[trackId] ?: TrackDownloadState(trackId)

    fun trackFor(trackId: Long): Track? = tracks[trackId]

    fun knownTracks(): Map<Long, Track> = tracks.toMap()

    fun savedRecords(): List<DownloadRecord> = records?.all().orEmpty()

    /** Enqueues a download; false when already active/downloaded or blocked by the network/space policy. */
    fun download(track: Track): Boolean {
        val quality = preferredQuality()
        synchronized(lock) {
            val current = stateFor(track.id)
            when (DownloadSchedulerRules.decide(
                existing = current.phase,
                active = active,
                wifiOnly = wifiOnly(),
                onWifi = onWifi(),
                availableBytes = availableBytes(),
            )) {
                DownloadSchedulerRules.Decision.ALREADY_DONE -> {
                    notice("「${track.name}」已下载过")
                    return false
                }
                DownloadSchedulerRules.Decision.ALREADY_ACTIVE -> {
                    notice("「${track.name}」正在下载中")
                    return false
                }
                DownloadSchedulerRules.Decision.WIFI_BLOCKED -> {
                    failNow(track, "仅 Wi-Fi 下载已开启，当前不是 Wi-Fi 网络")
                    return false
                }
                DownloadSchedulerRules.Decision.NO_SPACE -> {
                    failNow(track, "空间不足，无法开始下载")
                    return false
                }
                DownloadSchedulerRules.Decision.START, DownloadSchedulerRules.Decision.QUEUE -> Unit
            }
            tracks[track.id] = track
            pausedIds.remove(track.id)
            deletedIds.remove(track.id)
            val resumeBytes = records?.get(track.id)?.downloadedBytes ?: 0L
            notice("正在下载「${track.name}」（${DownloadQualityPlan.qualityLabel(quality)}）")
            logAction(track.id, if (resumeBytes > 0L) "resume" else "start", resumeBytes, null, "quality=$quality")
            if (active >= maxConcurrent) {
                waiting.addLast(track.id)
                update(TrackDownloadState(track.id, DownloadPhase.WAITING, downloadedBytes = resumeBytes))
                notice("已加入下载队列：「${track.name}」")
                logAction(track.id, "queue", resumeBytes, null, "active=$active")
            } else {
                active++
                start(track, quality)
            }
            return true
        }
    }

    /** Pauses a waiting/running download, keeping its `.part` file and record for a later resume. */
    fun pause(trackId: Long) {
        synchronized(lock) {
            if (waiting.remove(trackId)) {
                pausedIds += trackId
                val state = stateFor(trackId)
                update(state.copy(phase = DownloadPhase.PAUSED, bytesPerSecond = null))
                records?.get(trackId)?.let { records.save(it.copy(phase = DownloadPhase.PAUSED)) }
                notice("已暂停下载")
                logAction(trackId, "pause", state.downloadedBytes, state.totalBytes, "waiting")
                return
            }
            val job = jobs[trackId] ?: return
            pausedIds += trackId
            val state = stateFor(trackId)
            update(state.copy(phase = DownloadPhase.PAUSED, bytesPerSecond = null))
            records?.get(trackId)?.let { records.save(it.copy(phase = DownloadPhase.PAUSED)) }
            notice("已暂停下载")
            logAction(trackId, "pause", state.downloadedBytes, state.totalBytes, "running")
            job.cancel()
        }
    }

    /** Resumes a paused/failed download (or starts it again). */
    fun resume(track: Track): Boolean {
        pausedIds.remove(track.id)
        if (stateFor(track.id).phase == DownloadPhase.DONE) return false
        return download(track)
    }

    fun retry(track: Track): Boolean = resume(track)

    fun cancel(trackId: Long) {
        synchronized(lock) {
            if (waiting.remove(trackId)) {
                update(TrackDownloadState(trackId, DownloadPhase.IDLE))
                notice("已取消下载")
                logAction(trackId, "fail", 0, null, "cancelled-waiting")
                return
            }
            val job = jobs[trackId] ?: return
            val state = stateFor(trackId)
            update(state.copy(phase = DownloadPhase.FAILED, message = "已取消下载", bytesPerSecond = null))
            notice("已取消下载")
            logAction(trackId, "fail", state.downloadedBytes, state.totalBytes, "cancelled")
            job.cancel()
        }
    }

    fun cancelAll() {
        states.value.keys.toList().forEach { cancel(it) }
    }

    fun pauseAll() {
        states.value.filterValues { DownloadSchedulerRules.isActive(it.phase) }.keys.toList().forEach { pause(it) }
    }

    fun resumeAll() {
        states.value.filterValues { it.phase == DownloadPhase.PAUSED || it.phase == DownloadPhase.FAILED }
            .keys.toList().forEach { id -> tracks[id]?.let { resume(it) } }
    }

    fun delete(trackId: Long, deleteFile: Boolean = true) {
        synchronized(lock) {
            waiting.remove(trackId)
            jobs[trackId]?.cancel()
            val state = stateFor(trackId)
            if (deleteFile && state.uri != null) runCatching { deleteStored(state.uri) }
            records?.remove(trackId)
            deletedIds += trackId
            pausedIds.remove(trackId)
            tracks.remove(trackId)
            _states.value = _states.value - trackId
            notice("已删除下载记录")
            logAction(trackId, "fail", state.downloadedBytes, state.totalBytes, "deleted")
        }
    }

    fun clearCompleted() {
        val done = states.value.filterValues { it.phase == DownloadPhase.DONE }.keys.toList()
        done.forEach { delete(it, deleteFile = true) }
        records?.clearCompleted()
    }

    /** Enqueues every not-yet-downloaded track from a playlist/album; returns how many were accepted. */
    fun downloadAll(tracks: List<Track>): Int {
        val phases = tracks.associate { it.id to stateFor(it.id).phase }
        val candidates = DownloadSchedulerRules.batchCandidates(tracks.map { it.id }, phases)
        var accepted = 0
        candidates.forEach { id ->
            val track = tracks.firstOrNull { it.id == id } ?: return@forEach
            if (download(track)) accepted++
        }
        if (accepted > 0) notice("已加入 $accepted 首下载")
        return accepted
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
                val state = stateFor(track.id)
                if (pausedIds.contains(track.id)) {
                    update(state.copy(phase = DownloadPhase.PAUSED, bytesPerSecond = null))
                    logAction(track.id, "pause", state.downloadedBytes, state.totalBytes, "cancelled")
                } else {
                    update(state.copy(phase = DownloadPhase.FAILED, message = "已取消下载", bytesPerSecond = null))
                    logAction(track.id, "fail", state.downloadedBytes, state.totalBytes, "cancelled")
                }
                throw e
            } catch (t: Throwable) {
                val message = "下载失败：${short(t)}"
                update(stateFor(track.id).copy(phase = DownloadPhase.FAILED, message = message, bytesPerSecond = null))
                notice(message)
                records?.get(track.id)?.let { records.save(it.copy(phase = DownloadPhase.FAILED, message = message)) }
                logAction(track.id, "fail", stateFor(track.id).downloadedBytes, stateFor(track.id).totalBytes, short(t))
            } finally {
                jobs.remove(track.id)
                // A delete may have raced the cancellation above; the record must stay deleted.
                if (deletedIds.remove(track.id)) records?.remove(track.id)
                finish(track.id)
            }
        }
        jobs[track.id] = job
    }

    private fun finish(trackId: Long) {
        synchronized(lock) {
            active = (active - 1).coerceAtLeast(0)
            pausedIds.remove(trackId)
            while (true) {
                val next = waiting.removeFirstOrNull() ?: return
                val track = tracks[next] ?: continue
                active++
                start(track, preferredQuality())
                return
            }
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
                failure = "音源未返回地址"
                logDownload(track, "layer=resolve quality=$quality result=none")
                continue
            }
            if (resolved.isTrial) {
                failure = DownloadPreviewGate.MESSAGE
                logDownload(track, "layer=gate quality=$quality result=trial")
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
            if (DownloadPreviewGate.refuse(verdict, length, track.durationMs)) {
                failure = DownloadPreviewGate.MESSAGE
                logDownload(track, "layer=gate quality=$quality result=preview")
                continue
            }
            val resumedBytes = records?.get(track.id)?.downloadedBytes ?: 0L
            update(TrackDownloadState(
                track.id,
                DownloadPhase.DOWNLOADING,
                progress = DownloadResumeRules.progress(resumedBytes, length),
                downloadedBytes = resumedBytes,
                totalBytes = length,
                quality = quality,
            ))
            var lastBucket = -1L
            var lastBytes = resumedBytes
            var lastAt = System.currentTimeMillis()
            try {
                val saved = sink.save(track, url) { downloaded, total ->
                    val bucket = if (total != null && total > 0) downloaded * 100 / total else downloaded / (256 * 1024)
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastAt).coerceAtLeast(1L)
                        val speed = ((downloaded - lastBytes) * 1000L / elapsed).coerceAtLeast(0L)
                        lastBytes = downloaded
                        lastAt = now
                        update(TrackDownloadState(
                            track.id,
                            DownloadPhase.DOWNLOADING,
                            progress = DownloadResumeRules.progress(downloaded, total),
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            bytesPerSecond = speed,
                            quality = quality,
                        ))
                        logAction(track.id, "progress", downloaded, total, "quality=$quality")
                    }
                }
                val doneState = stateFor(track.id)
                update(doneState.copy(
                    phase = DownloadPhase.DONE,
                    progress = 1f,
                    fileName = saved.displayName,
                    uri = saved.audioUri,
                    bytesPerSecond = null,
                ))
                records?.save(
                    DownloadRecord(
                        track = track,
                        quality = quality,
                        url = url,
                        totalBytes = doneState.totalBytes,
                        downloadedBytes = doneState.downloadedBytes,
                        phase = DownloadPhase.DONE,
                        fileName = saved.displayName,
                        uri = saved.audioUri,
                    ),
                )
                notice("已保存到 ${saved.folder}：${saved.displayName}")
                // Never let a local-index refresh turn a successful download into a failure.
                runCatching { onCompleted?.invoke(track) }
                logAction(track.id, "done", doneState.downloadedBytes, doneState.totalBytes, "quality=$quality file=${saved.displayName}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure = storageErrorMessage(t)
                records?.save(
                    DownloadRecord(
                        track = track,
                        quality = quality,
                        url = url,
                        totalBytes = stateFor(track.id).totalBytes,
                        downloadedBytes = stateFor(track.id).downloadedBytes,
                        phase = DownloadPhase.FAILED,
                        message = failure,
                    ),
                )
                logDownload(track, "layer=write quality=$quality error=${t.javaClass.simpleName}: ${short(t)}")
                continue
            }
        }
        failure = failure ?: "下载失败"
        update(stateFor(track.id).copy(phase = DownloadPhase.FAILED, message = failure))
        notice(if (failure == DownloadPreviewGate.MESSAGE) failure else "下载失败：$failure")
        logAction(track.id, "fail", stateFor(track.id).downloadedBytes, stateFor(track.id).totalBytes, failure)
    }

    private fun failNow(track: Track, message: String) {
        tracks[track.id] = track
        update(TrackDownloadState(track.id, DownloadPhase.FAILED, message = message))
        notice(message)
        records?.save(DownloadRecord(track = track, phase = DownloadPhase.FAILED, message = message))
        logAction(track.id, "fail", 0L, null, message)
    }

    private fun storageErrorMessage(t: Throwable): String {
        val text = generateSequence(t) { it.cause }.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
        return when {
            text.contains("ENOSPC", true) || text.contains("No space left", true) -> "空间不足，下载已取消"
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
        runCatching { log.record("下载", "stage=download id=$trackId $detail") }
    }

    private fun logAction(trackId: Long, action: String, bytes: Long, total: Long?, reason: String? = null) {
        log(trackId, "action=$action bytes=$bytes/${total ?: -1}${reason?.let { " reason=$it" } ?: ""}")
    }

    private fun logDownload(track: Track, detail: String) {
        runCatching {
            log.record(
                "下载",
                "stage=download id=${track.id} name=${track.name} artist=${track.artistLine} $detail",
            )
        }
    }

    private fun short(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull().orEmpty().take(160)
}
