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
 * ## The two states that are easy to get wrong
 *
 *  - **A repository with no releases answers `404`, not an empty list.** Read as an error, that makes the
 *    updater report a failure on every fresh fork; it means "nothing published yet", so it is [UpToDate].
 *  - **Anonymous API calls are limited to 60 per hour per address.** Enough for a once-a-day check, not for
 *    one per launch, which is why [UpdateManager] throttles rather than calling this on every start.
 */
class UpdateChecker(
    private val owner: String,
    private val repo: String,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        if (owner.isBlank() || repo.isBlank()) {
            return@withContext UpdateCheck.Failed("未配置更新地址")
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            // Sent because GitHub rejects requests without one, and it also makes the traffic
            // identifiable in the API's own logs.
            .header("User-Agent", "YunYin/$currentVersion")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                // No releases published yet: this is the normal state of a young repository, not a fault.
                if (response.code == 404) return@withContext UpdateCheck.UpToDate
                if (response.code == 403) {
                    // Rate limited, or filtered by a proxy. Both are transient and unactionable now.
                    return@withContext UpdateCheck.Failed("请求过于频繁，请稍后再试")
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheck.Failed("服务器返回 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseRelease(body, currentVersion, "https://github.com/$owner/$repo/releases/latest")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, DNS filtered, TLS interrupted: all the same to the caller.
            UpdateCheck.Failed(e.message ?: "网络错误")
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
