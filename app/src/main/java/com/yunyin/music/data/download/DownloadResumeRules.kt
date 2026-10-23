package com.yunyin.music.data.download

/**
 * Pure rules for resumable downloads.
 *
 * A resume is only valid when the stored record still describes the URL the resolver just handed us: download
 * URLs are signed and expire, so a record from a previous session may point at an entirely different resource.
 * In that case the partial file is discarded and the download starts at zero.
 */
object DownloadResumeRules {

    /** Byte offset to request for [url] from a previous [record], 0 when there is nothing safe to resume. */
    fun resumeOffset(record: DownloadRecord?, url: String): Long {
        if (record == null) return 0L
        if (record.url != url) return 0L
        if (record.phase == DownloadPhase.DONE) return 0L
        return record.downloadedBytes.coerceAtLeast(0L)
    }

    /** HTTP Range header for [offset], null when the request should start at zero. */
    fun rangeHeader(offset: Long): String? = if (offset > 0L) "bytes=$offset-" else null

    /** Start byte from a `Content-Range: bytes 100-199/200` header, or null when absent/malformed. */
    fun contentRangeStart(header: String?): Long? {
        val value = header?.trim() ?: return null
        if (!value.startsWith("bytes", ignoreCase = true)) return null
        val range = value.substringAfter(' ', "").substringBefore('/')
        return range.substringBefore('-').toLongOrNull()
    }

    /** Total size from a Content-Range header (`bytes 100-199/200` → 200), or null. */
    fun contentRangeTotal(header: String?): Long? {
        val value = header?.trim() ?: return null
        val tail = value.substringAfter('/', "")
        return tail.toLongOrNull()?.takeIf { it > 0L }
    }

    /** A 206 at the requested offset is a real resume; a 200 means the server ignored Range. */
    fun responseIsResume(code: Int, requestedOffset: Long, contentRange: String?): Boolean =
        requestedOffset > 0L && code == 206 && contentRangeStart(contentRange) == requestedOffset

    /** The server ignored Range and sent the whole file; the partial file must be discarded and restarted. */
    fun mustRestart(code: Int, requestedOffset: Long): Boolean =
        requestedOffset > 0L && code == 200

    /**
     * Progress 0..1 for [downloaded] of [total], or null when the server did not declare a usable total.
     * The resumed bytes count toward progress because [downloaded] is always absolute.
     */
    fun progress(downloaded: Long, total: Long?): Float? =
        total?.takeIf { it > 0L }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }
}
