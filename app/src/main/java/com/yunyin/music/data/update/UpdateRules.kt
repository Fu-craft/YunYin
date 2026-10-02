package com.yunyin.music.data.update

/** A file attached to a release; for this app, the signed APK. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

/**
 * A release newer than the running build.
 *
 * [apk] is nullable on purpose: a release may exist with only notes, or with assets that are not
 * installable. That is not an error — the release page is still worth opening — so the UI offers
 * "打开下载页" whenever this is null rather than pretending there is nothing to update to.
 */
data class AvailableUpdate(
    val versionName: String,
    val notes: String,
    val apk: ReleaseAsset?,
    val pageUrl: String,
)

/**
 * The update check's pure rules.
 *
 * Kept apart from the network code so the parts most likely to be wrong — comparing versions, and
 * deciding which asset is the APK — are testable without HTTP. Both have a specific way of being wrong
 * that is worth naming:
 *
 *  - **Version comparison** must be numeric, not textual. `"3.10.0" < "3.9.0"` as strings, which would
 *    silently stop offering updates the moment the minor version reached double digits.
 *  - **Asset selection** must require https on a GitHub host, because the URL comes out of a JSON
 *    document and then goes to a downloader. Without that check, a release whose asset list was edited
 *    to point elsewhere would turn into a downloaded-and-executed file.
 */
object UpdateRules {

    /**
     * Where a release asset may be downloaded from.
     *
     * `github.com` is the address in the API response; the bytes actually arrive from
     * `release-assets.githubusercontent.com` or `objects.githubusercontent.com` after a redirect (measured:
     * a `cli/cli` release asset resolved to `release-assets.githubusercontent.com`). Both legs are allowed
     * because either can appear as the URL depending on how the release was created.
     */
    private val TRUSTED_HOSTS = listOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "codeload.github.com",
    )

    /**
     * The numeric parts of a version, ignoring the decoration.
     *
     * `v3.8.2` → `[3, 8, 2]`; `3.8.1` → `[3, 8, 1]`; `3.9.0-beta.1` → `[3, 9, 0]`. The `v` prefix is
     * what Git tags carry, and the `-beta` suffix is dropped rather than treated as a lower release,
     * because pre-releases are filtered out by the endpoint the checker calls.
     */
    fun versionParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }

    /**
     * Three-way comparison of two versions: negative when [current] is older than [remote].
     *
     * Missing components count as zero, so `3.9` and `3.9.0` are equal rather than one being older.
     */
    fun compareVersions(current: String, remote: String): Int {
        val a = versionParts(current)
        val b = versionParts(remote)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    /** Whether [remote] is strictly newer than [current]. An unparseable remote is never "newer". */
    fun isNewer(current: String, remote: String): Boolean =
        versionParts(current).isNotEmpty() &&
            versionParts(remote).isNotEmpty() &&
            compareVersions(current, remote) < 0

    /**
     * Whether an asset URL is safe to download from.
     *
     * Requires https and one of [TRUSTED_HOSTS] (or a subdomain of one). See the class comment for why
     * this is not merely defensive.
     */
    fun isTrustedDownloadUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = url.substring("https://".length)
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * The APK to install, or null when the release has no installable asset.
     *
     * Prefers a name containing `release` — a project commonly attaches both a debug and a release APK,
     * and installing debug over release fails the signature check, which looks like a broken updater
     * rather than a wrong choice of file.
     */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? {
        val installable = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && isTrustedDownloadUrl(it.url)
        }
        return installable.firstOrNull { it.name.contains("release", ignoreCase = true) }
            ?: installable.firstOrNull()
    }
}
