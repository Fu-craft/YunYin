package com.yunyin.music.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What a check found. */
sealed interface UpdateCheck {
    /** A newer release exists. */
    data class Newer(val update: AvailableUpdate) : UpdateCheck

    /** This build is current, or the repository has published nothing yet. */
    data object UpToDate : UpdateCheck

    /**
     * The check could not be completed.
     *
     * Separate from [UpToDate] so the UI can be honest — but it is deliberately *quiet*: a failed check is
     * the normal state on a network that filters GitHub, and reporting it as an error every launch would
     * be noise the user cannot act on.
     */
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Reads the newest release from GitHub.
 *
 * ## Why GitHub, and why no server
 *
 * A release is a file plus a version, hosted and served for free by a repository that already exists, so an
 * update channel needs no infrastructure of this project's own. Measured from this network: DNS resolves
 * `api.github.com` in ~15ms, `releases/latest` answers in ~600ms, and a release asset downloads over https
 * (the URL 302s from `github.com` to `release-assets.githubusercontent.com`, and both legs were reached).
 *
 * ## Two paths, because the API is rate limited and sometimes refused
 *
 * `api.github.com` is the primary source: it is the only one carrying **asset URLs**, so it is the only one
 * that can lead to an automatic install. Its anonymous allowance is 60 requests per hour **per IP**, which
 * is easily spent when several devices share a carrier NAT — and a filtered network may answer `403` for
 * reasons that have nothing to do with rate limiting.
 *
 * So when the API refuses, the check falls back to `releases.atom`, which is **not rate limited** (measured
 * 200 OK while the API's own headers still showed usage). It cannot supply an asset URL, so an update found
 * this way is offered as "打开下载页" rather than a download — which is honest, and better than reporting a
 * failure for an update that does exist.
 *
 * ## The states that are easy to get wrong
 *
 *  - **A repository with no releases answers `404`, not an empty list.** Read as an error, that makes the
 *    updater report a failure on every fresh fork; it means "nothing published yet", so it is [UpToDate].
 *  - **`403` is not necessarily "too many requests".** GitHub sends `403` for rate limiting, but a proxy or
 *    a filtered network sends it too. The body is inspected before claiming anything about frequency, and
 *    the atom fallback runs either way.
 */
class UpdateChecker(
    private val owner: String,
    private val repo: String,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient(),
) {

    /** Why the API path was refused, for the message when the fallback finds nothing either. */
    private var refusal: String? = null

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        if (owner.isBlank() || repo.isBlank()) {
            return@withContext UpdateCheck.Failed("未配置更新地址")
        }
        refusal = null
        // The API is tried first because only it can point at an installable APK.
        apiCheck()?.let { return@withContext it }
        // Refused: ask the feed that is not rate limited.
        atomCheck()?.let { return@withContext it }
        UpdateCheck.Failed(refusal ?: "检查更新失败")
    }

    /**
     * The API path. Returns null when the request was **refused** (403/429), meaning the caller should try
     * the fallback rather than treat it as an answer.
     */
    private fun apiCheck(): UpdateCheck? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            // Sent because GitHub rejects requests without one, and it also identifies this traffic.
            .header("User-Agent", "YunYin/$currentVersion")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    // No releases published yet: the normal state of a young repository, not a fault.
                    response.code == 404 -> UpdateCheck.UpToDate
                    response.code == 403 || response.code == 429 -> {
                        // Only call it rate limiting when GitHub says so; a middlebox 403 means
                        // something else entirely, and naming it wrongly sends the user to wait for
                        // a limit that is not there.
                        refusal = if (body.contains("rate limit", ignoreCase = true)) {
                            "请求过于频繁，请稍后再试"
                        } else {
                            "GitHub 拒绝了请求（HTTP ${response.code}）"
                        }
                        null
                    }
                    !response.isSuccessful -> UpdateCheck.Failed("服务器返回 HTTP ${response.code}")
                    else -> parseRelease(body, currentVersion, "https://github.com/$owner/$repo/releases/latest")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, DNS filtered, TLS interrupted: all the same to the caller.
            UpdateCheck.Failed(e.message ?: "网络错误")
        }
    }

    /**
     * The atom feed: no rate limit, but no asset URLs either.
     *
     * Returns null when it could not answer, so the caller can report the API's refusal instead.
     */
    private fun atomCheck(): UpdateCheck? {
        val request = Request.Builder()
            .url("https://github.com/$owner/$repo/releases.atom")
            .header("User-Agent", "YunYin/$currentVersion")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                parseAtomRelease(response.body?.string().orEmpty(), currentVersion)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Shorter than the API client's: an update check must never make the user wait, and there is
            // nothing to lose by trying again later.
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Turns one release document into a result.
 *
 * A top-level function rather than a private method so it can be tested against a captured payload: the
 * field names it reads are the part of this feature most likely to be quietly wrong, and "quietly wrong"
 * here means an updater that reports "已是最新" forever because it looked for `tag` instead of `tag_name`.
 *
 * Failures are [UpdateCheck.Failed] rather than [UpdateCheck.UpToDate]: a body that does not parse means
 * something interfered (a captive portal, a proxy), and silently claiming "you are current" would hide
 * that until the user noticed they were several versions behind.
 */
internal fun parseRelease(body: String, currentVersion: String, fallbackPageUrl: String): UpdateCheck {
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        return UpdateCheck.Failed("更新信息无法解析")
    }
    // `tag_name` is the field the release carries; `name` is a fallback because a hand-made release can
    // have a title but a missing tag in some API responses.
    val tag = json.optString("tag_name").ifBlank { json.optString("name") }
    if (tag.isBlank()) return UpdateCheck.Failed("更新信息不完整")
    if (!UpdateRules.isNewer(currentVersion, tag)) return UpdateCheck.UpToDate

    val assetsJson = json.optJSONArray("assets")
    val assets = (0 until (assetsJson?.length() ?: 0)).mapNotNull { i ->
        val a = assetsJson?.optJSONObject(i) ?: return@mapNotNull null
        val url = a.optString("browser_download_url")
        if (url.isBlank()) return@mapNotNull null
        ReleaseAsset(
            name = a.optString("name"),
            url = url,
            sizeBytes = a.optLong("size"),
        )
    }
    return UpdateCheck.Newer(
        AvailableUpdate(
            versionName = tag.removePrefix("v").removePrefix("V"),
            notes = json.optString("body").trim(),
            apk = UpdateRules.pickApk(assets),
            // `html_url` is the release's own page; the fallback keeps "打开下载页" working if it is absent.
            pageUrl = json.optString("html_url").ifBlank { fallbackPageUrl },
        ),
    )
}

/**
 * Turns a release *atom feed* into a result.
 *
 * The fallback used when the API refuses. It carries no asset URLs, so an update found here is offered as a
 * download **page** rather than a download — the release exists, so reporting "no update" (or worse, a
 * failure) would be wrong, but there is nothing to install automatically.
 *
 * The tag is read from the entry's link (`.../releases/tag/v3.9.0`), which is the field GitHub fills in for
 * every release. The feed's own `<title>` is the repository name, not a version, so the first `<title>`
 * **inside** an `<entry>` is the one that matters — reading the document's first title would compare
 * against the repository name and find nothing, forever.
 *
 * Returns null when the feed has no usable tag, so the caller keeps the API's refusal as the reason.
 */
internal fun parseAtomRelease(body: String, currentVersion: String): UpdateCheck? {
    // Shape first. A captive portal or proxy answers with HTML, which also has no `<entry>` — and treating
    // "no entry" as "no releases" would claim the user is up to date on the strength of an error page.
    if (!body.contains("<feed")) return null
    // Entries only: stops the feed-level <title>/<link> from being mistaken for a release.
    val entry = body.substringAfter("<entry>", "").substringBefore("</entry>")
    if (entry.isBlank()) return UpdateCheck.UpToDate // a real feed with no releases has no entries

    val href = Regex("""href="([^"]+)"""")
        .find(entry)?.groupValues?.get(1)
        ?: return null
    val tag = href.substringAfterLast("/releases/tag/", "")
    if (tag.isBlank() || tag == href) return null // not a release-tag link; cannot tell what this is

    if (!UpdateRules.isNewer(currentVersion, tag)) return UpdateCheck.UpToDate
    return UpdateCheck.Newer(
        AvailableUpdate(
            versionName = tag.removePrefix("v").removePrefix("V"),
            // The entry's content is HTML-escaped release notes; it is left out rather than rendered
            // wrong, so the sheet simply shows no notes for an update found this way.
            notes = "",
            apk = null,
            pageUrl = href,
        ),
    )
}
