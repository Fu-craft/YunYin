package com.yunyin.music.data.lxsource

/**
 * When NetEase's own answer makes the custom source still worth consulting.
 *
 * The import-time optimisation: a NetEase track whose built-in response is a **full** URL is resolved without a
 * source round trip at all. The source is only needed when it is enabled *and* NetEase produced nothing usable (no
 * URL, a trial fragment, or an unavailable result). Kept pure so the rule is testable and so the reasoning lives in
 * one place instead of being an `if` in the middle of the resolver.
 */
internal object ResolutionConsultPolicy {

    fun needsSource(sourceEnabled: Boolean, netease: com.yunyin.music.data.net.NeteaseClient.SongUrl?): Boolean {
        if (!sourceEnabled) return false
        if (netease == null) return true
        return netease.isTrial || netease.unavailable || !netease.playable
    }
}
