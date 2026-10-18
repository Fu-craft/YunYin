package com.yunyin.music.data.lxsource

import java.net.URI

/**
 * Mirror rewrite for GitHub script URLs.
 *
 * ## Why
 *
 * `raw.githubusercontent.com` is frequently unreachable from mainland China (DNS poisoning, SNI blocking, reset
 * routes), while the jsDelivr mirrors of the *same file* usually work. A user pasting a maintained source URL should
 * not have to know that — so the import tries the original URL first and then known mirrors of the same repository
 * path. The result is the same bytes; only the origin rewrite differs.
 *
 * ## What this object does *not* do
 *
 * It is a **pure URL rewrite**, not a policy. Every candidate still goes through [LxSourceUrlPolicy] and the
 * fetcher's DNS/private-address/redirect checks, so a mirror cannot be used to reach a private address. The user's
 * pasted URL is always candidate 0 and is what gets persisted as the origin (that is what 更新 re-fetches).
 */
object LxSourceMirrorRules {

    /** One download attempt: [label] is shown when reporting which address failed or succeeded. */
    data class Candidate(val label: String, val url: String, val mirrored: Boolean)

    const val ORIGINAL_LABEL = "原始地址"
    const val JSDELIVR_LABEL = "jsDelivr 镜像"
    const val FASTLY_LABEL = "fastly 镜像"

    /** Original first, then mirror(s). Non-GitHub URLs have exactly one candidate. */
    fun candidates(original: String): List<Candidate> =
        listOf(Candidate(ORIGINAL_LABEL, original, mirrored = false)) + mirrors(original)

    /** jsDelivr first (more reliable from this region), then its fastly endpoint. Empty for non-GitHub URLs. */
    fun mirrors(original: String): List<Candidate> {
        val githubPath = githubPath(original) ?: return emptyList()
        return listOf(
            Candidate(JSDELIVR_LABEL, "https://cdn.jsdelivr.net/gh/$githubPath", mirrored = true),
            Candidate(FASTLY_LABEL, "https://fastly.jsdelivr.net/gh/$githubPath", mirrored = true),
        )
    }

    /**
     * `owner/repo@branch/path` for a GitHub raw/blob URL, or null.
     *
     * Supported forms:
     *  - `https://raw.githubusercontent.com/<owner>/<repo>/<branch>/<path…>`
     *  - `https://github.com/<owner>/<repo>/raw/<branch>/<path…>`
     *  - `https://github.com/<owner>/<repo>/blob/<branch>/<path…>[?raw=true]`
     *
     * A branch containing `/` cannot be disambiguated from the path; the first segment after `raw`/`blob` is taken
     * as the branch, which is correct for the usual `main`/`master`/tag URLs. If any segment is empty or `..`, no
     * rewrite is produced rather than a guess.
     */
    fun githubPath(original: String): String? {
        val uri = try {
            URI(original.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val rawPath = uri.path.orEmpty().trim('/')
        val segments = rawPath.split('/')
        if (segments.size < 4) return null

        val parts = when (host) {
            "raw.githubusercontent.com" -> segments
            "github.com" -> {
                if (segments.size < 5) return null
                val kind = segments[2]
                if (kind != "raw" && kind != "blob") return null
                segments.filterIndexed { index, _ -> index != 2 }
            }
            else -> return null
        }
        if (parts.size < 4) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        val branch = parts[2]
        val path = parts.drop(3).joinToString("/")
        if (owner.isEmpty() || repo.isEmpty() || branch.isEmpty() || path.isEmpty()) return null
        return "$owner/$repo@$branch/$path"
    }
}
