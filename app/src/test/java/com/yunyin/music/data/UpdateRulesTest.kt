package com.yunyin.music.data

import com.yunyin.music.data.update.ReleaseAsset
import com.yunyin.music.data.update.UpdateCheck
import com.yunyin.music.data.update.UpdateRules
import com.yunyin.music.data.update.parseRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update channel's two failure-prone rules: comparing versions, and choosing which asset to install.
 *
 * Both are pure, and both have a way of being wrong that only shows up in production — which is why they
 * are tested rather than eyeballed:
 *
 *  - A *textual* version comparison works perfectly until the version reaches double digits, then silently
 *    stops offering updates ("3.10.0" < "3.9.0" as strings). Nothing would look broken; users would just
 *    stay on 3.9 forever.
 *  - An unchecked asset URL goes straight into a downloader and then into the installer. The check that it
 *    is https on a GitHub host is the difference between "downloads the release" and "downloads whatever
 *    the JSON happened to point at".
 */
class UpdateRulesTest {

    // ---------------------------------------------------------------- version comparison

    @Test
    fun `a higher patch is newer`() {
        assertTrue(UpdateRules.isNewer("3.8.1", "3.8.2"))
        assertFalse(UpdateRules.isNewer("3.8.2", "3.8.1"))
    }

    @Test
    fun `ten is newer than nine, which a string comparison gets wrong`() {
        // The bug this exists to prevent: "3.10.0" < "3.9.0" lexicographically, so a textual comparison
        // would report "not newer" and permanently stop offering updates at the 3.9 -> 3.10 boundary.
        assertTrue("3.10.0" < "3.9.0")   // the string result, i.e. what must NOT decide this
        assertTrue(UpdateRules.isNewer("3.9.0", "3.10.0"))
        assertFalse(UpdateRules.isNewer("3.10.0", "3.9.0"))
    }

    @Test
    fun `a v prefixed git tag compares the same as a bare version`() {
        // GitHub tags are conventionally `v3.8.2` while BuildConfig.VERSION_NAME has no `v`.
        assertTrue(UpdateRules.isNewer("3.8.1", "v3.8.2"))
        assertFalse(UpdateRules.isNewer("3.8.2", "v3.8.1"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertFalse(UpdateRules.isNewer("3.9.0", "3.9"))
        assertFalse(UpdateRules.isNewer("3.9", "3.9.0"))
        assertTrue(UpdateRules.isNewer("3.9", "3.9.1"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(UpdateRules.isNewer("3.8.1", "3.8.1"))
        assertEquals(0, UpdateRules.compareVersions("3.8.1", "v3.8.1"))
    }

    @Test
    fun `an unparseable remote is never treated as an update`() {
        // A tag like "nightly" must not become an offer to install.
        assertFalse(UpdateRules.isNewer("3.8.1", "nightly"))
        assertFalse(UpdateRules.isNewer("3.8.1", ""))
    }

    @Test
    fun `version parts ignore a suffix`() {
        assertEquals(listOf(3, 9, 0), UpdateRules.versionParts("3.9.0-beta.1"))
        assertEquals(listOf(3, 9, 0), UpdateRules.versionParts("v3.9.0"))
        assertEquals(emptyList<Int>(), UpdateRules.versionParts("no digits here"))
    }

    // ---------------------------------------------------------------- download URL trust

    @Test
    fun `github download hosts are trusted`() {
        assertTrue(UpdateRules.isTrustedDownloadUrl(
            "https://github.com/Fu-craft/YunYin/releases/download/v3.9.0/app-release.apk"))
        assertTrue(UpdateRules.isTrustedDownloadUrl(
            "https://objects.githubusercontent.com/github-production-release-asset/x/app.apk"))
        assertTrue(UpdateRules.isTrustedDownloadUrl(
            "https://release-assets.githubusercontent.com/github-production-release-asset/x/app.apk"))
    }

    @Test
    fun `an unrelated host is refused`() {
        assertFalse(UpdateRules.isTrustedDownloadUrl("https://evil.example.com/app.apk"))
    }

    @Test
    fun `a lookalike host is refused`() {
        // `notgithub.com` must not pass by merely *containing* "github.com".
        assertFalse(UpdateRules.isTrustedDownloadUrl("https://notgithub.com/app.apk"))
        assertFalse(UpdateRules.isTrustedDownloadUrl("https://github.com.evil.example/app.apk"))
    }

    @Test
    fun `plain http is refused`() {
        // An unencrypted APK could be replaced in transit, and it would then be installed.
        assertFalse(UpdateRules.isTrustedDownloadUrl("http://github.com/x/app.apk"))
    }

    // ---------------------------------------------------------------- asset choice

    private fun asset(name: String, url: String = "https://github.com/o/r/releases/download/v1/$name") =
        ReleaseAsset(name = name, url = url, sizeBytes = 1024)

    @Test
    fun `the release apk is preferred over the debug one`() {
        // Installing debug over release fails the signature check, which reads as a broken updater; the
        // name preference is what avoids offering it.
        val picked = UpdateRules.pickApk(listOf(asset("app-debug.apk"), asset("app-release.apk")))
        assertEquals("app-release.apk", picked?.name)
    }

    @Test
    fun `a lone apk is picked when there is no release variant`() {
        assertEquals("yunyin.apk", UpdateRules.pickApk(listOf(asset("yunyin.apk")))?.name)
    }

    @Test
    fun `an untrusted apk is never picked`() {
        val picked = UpdateRules.pickApk(listOf(asset("app-release.apk", "https://evil.example.com/a.apk")))
        assertNull(picked)
    }

    @Test
    fun `a release with no apk yields null, which the UI handles as open-the-page`() {
        assertNull(UpdateRules.pickApk(listOf(asset("checksums.txt"), asset("notes.md"))))
        assertNull(UpdateRules.pickApk(emptyList()))
    }

    // ---------------------------------------------------------------- release document parsing
    //
    // Exercised against the field names a real GitHub release actually uses, confirmed by capturing a
    // live `/releases/latest` response: `tag_name`, `body`, `html_url`, and assets carrying `name`,
    // `size` and `browser_download_url`. A parser that looked for the wrong names would not crash — it
    // would simply decide there is never an update, which is the worst kind of bug here.

    @Test
    fun `a newer release parses into an offer with its apk`() {
        val body = """
            {
              "tag_name": "v3.9.0",
              "name": "云音 3.9.0",
              "html_url": "https://github.com/Fu-craft/YunYin/releases/tag/v3.9.0",
              "body": "更新说明",
              "assets": [
                {"name": "app-debug.apk", "size": 100,
                 "browser_download_url": "https://github.com/Fu-craft/YunYin/releases/download/v3.9.0/app-debug.apk"},
                {"name": "app-release.apk", "size": 90,
                 "browser_download_url": "https://github.com/Fu-craft/YunYin/releases/download/v3.9.0/app-release.apk"}
              ]
            }
        """.trimIndent()

        val result = parseRelease(body, currentVersion = "3.8.1", fallbackPageUrl = "https://example.invalid")
        assertTrue(result is UpdateCheck.Newer)
        val update = (result as UpdateCheck.Newer).update
        assertEquals("3.9.0", update.versionName)
        assertEquals("更新说明", update.notes)
        assertEquals("app-release.apk", update.apk?.name)
        assertEquals("https://github.com/Fu-craft/YunYin/releases/tag/v3.9.0", update.pageUrl)
    }

    @Test
    fun `the same or an older release is not an offer`() {
        val same = """{"tag_name":"v3.8.1","assets":[]}"""
        assertTrue(parseRelease(same, "3.8.1", "x") is UpdateCheck.UpToDate)
        val older = """{"tag_name":"v3.8.0","assets":[]}"""
        assertTrue(parseRelease(older, "3.8.1", "x") is UpdateCheck.UpToDate)
    }

    @Test
    fun `a release with no apk is still offered, to be opened in a browser`() {
        val body = """{"tag_name":"v3.9.0","html_url":"https://github.com/x/y/releases/tag/v3.9.0","assets":[]}"""
        val result = parseRelease(body, "3.8.1", "https://fallback.invalid")
        assertTrue(result is UpdateCheck.Newer)
        // apk == null is what makes the sheet offer "打开下载页" instead of a download that cannot happen.
        assertNull((result as UpdateCheck.Newer).update.apk)
        assertEquals("https://github.com/x/y/releases/tag/v3.9.0", result.update.pageUrl)
    }

    @Test
    fun `an unparseable body is a failure, not a silent up-to-date`() {
        // A captive portal or proxy returning HTML must not be read as "you are current".
        val result = parseRelease("<html>proxy error</html>", "3.8.1", "x")
        assertTrue("got $result", result is UpdateCheck.Failed)
    }

    @Test
    fun `a release with no tag is a failure`() {
        assertTrue(parseRelease("""{"name":"","assets":[]}""", "3.8.1", "x") is UpdateCheck.Failed)
    }

    @Test
    fun `an asset with an untrusted url is not offered for install`() {
        val body = """
            {
              "tag_name": "v3.9.0",
              "assets": [
                {"name": "app-release.apk", "size": 1,
                 "browser_download_url": "https://evil.example.com/app-release.apk"}
              ]
            }
        """.trimIndent()
        val result = parseRelease(body, "3.8.1", "https://github.com/x/y/releases")
        assertTrue(result is UpdateCheck.Newer)
        // The release is still offered (its page is legitimate) but nothing installable is attached.
        assertNull((result as UpdateCheck.Newer).update.apk)
    }

    @Test
    fun `a missing html_url falls back to the caller's page`() {
        val body = """{"tag_name":"v3.9.0","assets":[]}"""
        val result = parseRelease(body, "3.8.1", "https://github.com/o/r/releases/latest")
        assertEquals(
            "https://github.com/o/r/releases/latest",
            (result as UpdateCheck.Newer).update.pageUrl,
        )
    }
}
