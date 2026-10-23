package com.yunyin.music.data.download

import com.yunyin.music.core.Track

/** The phases a single track download moves through. Mirrors what the UI can honestly show. */
enum class DownloadPhase { IDLE, WAITING, RESOLVING, DOWNLOADING, PAUSED, DONE, FAILED }

/**
 * UI state of one track download.
 *
 * [message] is user-facing and only set for [DownloadPhase.FAILED]; a failure is retryable, which is why the same
 * track id can go FAILED → DOWNLOADING again.
 */
data class TrackDownloadState(
    val trackId: Long,
    val phase: DownloadPhase = DownloadPhase.IDLE,
    /** 0..1 while [DownloadPhase.DOWNLOADING]; null when the total length is unknown. */
    val progress: Float? = null,
    val message: String? = null,
    val fileName: String? = null,
    /** The MediaStore content:// URI once the audio is published. */
    val uri: String? = null,
    /** Absolute bytes written so far, including bytes resumed from a previous session. */
    val downloadedBytes: Long = 0L,
    /** Absolute total from Content-Length/Content-Range; null when the server did not say. */
    val totalBytes: Long? = null,
    /** Instantaneous bytes/second, for the centre's active rows; null when unknown/not downloading. */
    val bytesPerSecond: Long? = null,
    /** Quality tier the current attempt resolved, when known. */
    val quality: String? = null,
) {
    val isActive: Boolean
        get() = phase == DownloadPhase.WAITING || phase == DownloadPhase.RESOLVING || phase == DownloadPhase.DOWNLOADING

    /** User-visible size/progress summary used by the download centre. */
    fun sizeLabel(): String {
        val total = totalBytes ?: downloadedBytes
        if (total <= 0L) return "—"
        val mb = total / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.1f MB", mb)
    }

    /** "下载中 45%" style text for the action sheet / content description. */
    fun statusLabel(): String = when (phase) {
        DownloadPhase.IDLE -> "下载"
        DownloadPhase.WAITING -> "等待下载"
        DownloadPhase.RESOLVING -> "正在解析地址"
        DownloadPhase.DOWNLOADING -> {
            val percent = progress?.let { "${(it * 100).toInt()}%" }
            if (percent == null) "下载中" else "下载中 $percent"
        }
        DownloadPhase.PAUSED -> "已暂停"
        DownloadPhase.DONE -> "已下载"
        DownloadPhase.FAILED -> message ?: "下载失败"
    }

    companion object {
        fun idle(track: Track): TrackDownloadState = TrackDownloadState(track.id)
    }
}

/** The preview probe's verdict, mirrored here so the manager's public API does not expose an internal type. */
enum class DownloadProbeVerdict { FULL, PREVIEW_CLIP, UNKNOWN }

/**
 * The destination for one download. An interface so the manager's queueing/state machine is unit-testable without
 * Android, with [MediaStoreAudioWriter] as the production implementation.
 */
interface DownloadSink {
    suspend fun save(track: Track, url: String, onProgress: (downloaded: Long, total: Long?) -> Unit): SavedDownload
}

/** One published download: the audio row plus the best-effort cover row. */
data class SavedDownload(
    val audioUri: String,
    val displayName: String,
    /** `Music/云音`, `Music` or `Music/YunYin` — which folder the write actually succeeded into. */
    val folder: String,
    val coverUri: String? = null,
)

/**
 * Folder fallback order for the MediaStore write.
 *
 * `Music/云音` is the intended collection, but a Chinese sub-folder under Music is exactly the kind of path some
 * vendor ROMs refuse; the download then retries `Music` and finally the ASCII `Music/YunYin` and reports which one
 * worked, instead of failing the whole download on the first insert.
 */
object DownloadStoragePlan {

    val folders: List<String> = listOf("Music/云音", "Music", "Music/YunYin")

    /** The folders to try after [failedFolder], in order. */
    fun after(failedFolder: String): List<String> =
        folders.dropWhile { it != failedFolder }.drop(1).ifEmpty { emptyList() }
}

/**
 * Refuses to save a trial fragment.
 *
 * ## The corrected rule
 *
 * Only a **classified** preview is a reason to refuse. A probe that cannot determine the size (HEAD blocked, no
 * Content-Length, range refused → `UNKNOWN` with no length) must not veto the download: the playback path does not
 * treat it as a preview either, and on real CDNs an advisory HEAD failing is common. An `UNKNOWN` verdict with a
 * *length below the plausible minimum* is still not positive proof — the probe's own classifier would have returned
 * `PREVIEW_CLIP` if the length were below its conservative ratio — so it is allowed through as well. Positive evidence
 * comes from the resolver (`SongUrl.isTrial`, checked in the manager) or from `PREVIEW_CLIP`.
 */
internal object DownloadPreviewGate {

    const val MESSAGE = "会员歌曲：仅能获取试听片段，已取消下载"

    fun refuse(
        verdict: DownloadProbeVerdict,
        contentLength: Long?,
        trackDurationMs: Long,
    ): Boolean = verdict == DownloadProbeVerdict.PREVIEW_CLIP
}

/**
 * Content sniff for a download response.
 *
 * Some CDNs answer a login/error page or a JSON error with HTTP 200; writing that body would produce a "song" file
 * that no player can play. Only the first chunk is inspected, so this is cheap.
 */
object AudioResponseCheck {

    fun failure(prefix: ByteArray, contentType: String?): String? {
        val head = String(prefix, 0, minOf(prefix.size, 1024), Charsets.UTF_8).trimStart()
        if (head.isEmpty()) return "返回的不是音频（内容为空）"
        if (head.startsWith("<") || head.contains("<html", ignoreCase = true)) {
            return "返回的不是音频（像是网页）"
        }
        if (head.startsWith("{") || head.startsWith("[")) return "返回的不是音频（像是 JSON 错误）"
        if (contentType != null && (contentType.contains("text/", true) || contentType.contains("json", true))) {
            return "返回的不是音频（响应类型：$contentType）"
        }
        return null
    }
}
