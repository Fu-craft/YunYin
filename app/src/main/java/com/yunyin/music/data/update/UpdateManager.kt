package com.yunyin.music.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What the update UI shows. */
sealed interface UpdateUiState {
    /** Nothing to show; the sheet is closed and the row reads "点击检查". */
    data object Idle : UpdateUiState

    /** A newer version exists and has not been dismissed; the sheet is offered. */
    data class Available(val update: AvailableUpdate) : UpdateUiState

    /**
     * A newer version exists but the user skipped it.
     *
     * Deliberately distinct from [Idle] so the Settings row can still say "有新版本" — the offer stays
     * reachable on purpose — while the **sheet stays closed**. Skipping has to actually dismiss
     * something, or the tap appears to do nothing.
     */
    data class AvailableSkipped(val update: AvailableUpdate) : UpdateUiState

    /** Downloading [update]; the sheet shows progress and cannot be dismissed. */
    data class Downloading(val progress: Int, val update: AvailableUpdate) : UpdateUiState

    /**
     * Something went wrong, with a message worth showing.
     *
     * Carries the [update] when there is one, so the failure can still offer "打开下载页" instead of
     * dead-ending on an error the user cannot act on.
     */
    data class Failed(val message: String, val update: AvailableUpdate? = null) : UpdateUiState
}

/**
 * The app's update channel: check a GitHub release, download its APK, hand it to the system installer.
 *
 * ## Why there is no server
 *
 * The repository already exists and GitHub serves releases for free, so the "cloud" in "cloud delivery" is
 * the project's own release page. Nothing is deployed, nothing is maintained, and no address is baked into
 * the shipped code beyond the repository it came from.
 *
 * ## The three rules that keep this from being a liability
 *
 *  - **It must never block or break startup.** Every failure here ends as a quiet [UpdateUiState.Failed] or
 *    a silent [UpdateUiState.Idle]; none of it is on the launch path. This app has already been killed by a
 *    crash-on-open once, and an updater that can do that is worse than no updater.
 *  - **It must not be able to install something unexpected.** The download URL is checked against GitHub's
 *    hosts ([UpdateRules.isTrustedDownloadUrl]) before a byte is fetched, and the installed file is a
 *    `content://` URI the system package installer reads — the app never executes anything itself.
 *  - **Android requires the user to confirm.** There is no silent install; the app can only *ask*, which is
 *    the correct behaviour for something that replaces the running program.
 */
class UpdateManager(
    private val context: Context,
    private val owner: String,
    private val repo: String,
    private val currentVersion: String,
    private val settings: com.yunyin.music.data.SettingsStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * A one-shot message for the caller to surface, then clear.
     *
     * Needed because a *successful* check that finds nothing produces no visible state change: the sheet
     * stays closed and the row still reads "点击检查", so a user who just tapped "检查更新" would have no
     * way to tell the tap did anything. "已是最新版本" is the whole answer to that tap, and it has nowhere
     * else to live.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    private val checker = UpdateChecker(owner, repo, currentVersion)

    /** Whether an update check is worth doing at all in this build. */
    val configured: Boolean get() = owner.isNotBlank() && repo.isNotBlank()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // No read timeout: an APK is tens of megabytes on a slow connection, and a fixed timeout would
            // abort a download that is progressing perfectly well. Progress reporting, not a deadline, is
            // what tells the user it is alive.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Runs an automatic check if one is due.
     *
     * Rate-limited by [AUTO_CHECK_INTERVAL_MS] and skipped once the user has dismissed this exact version,
     * so the app can call it on every launch without nagging or burning the API's anonymous allowance.
     */
    fun autoCheckIfDue() {
        if (!configured) return
        val now = System.currentTimeMillis()
        if (now - settings.updateLastCheckMs < AUTO_CHECK_INTERVAL_MS) return
        settings.updateLastCheckMs = now
        check()
    }

    /**
     * Checks now, whatever the throttle says — what the "检查更新" row does.
     *
     * [force] also clears a previous skip, because asking explicitly is a request to be told, and sets
     * [notice] so the *positive* answers ("已是最新") reach the user; an automatic check stays silent about
     * them on purpose.
     */
    fun check(force: Boolean = false) {
        if (!configured) {
            _state.value = UpdateUiState.Failed("未配置更新地址")
            return
        }
        if (force) settings.updateSkippedVersion = ""
        scope.launch {
            when (val result = checker.check()) {
                is UpdateCheck.Newer ->
                    _state.value =
                        if (!force && settings.updateSkippedVersion == result.update.versionName) {
                            UpdateUiState.AvailableSkipped(result.update)
                        } else {
                            UpdateUiState.Available(result.update)
                        }
                // A successful check that finds nothing is a positive answer, not a failure. It also
                // resets any stale sheet, so a previously-found update that was never acted on cannot
                // linger in the state after it has been superseded.
                is UpdateCheck.UpToDate -> {
                    _state.value = UpdateUiState.Idle
                    if (force) _notice.value = "已是最新版本"
                }
                is UpdateCheck.Failed -> {
                    _state.value = UpdateUiState.Failed(result.message)
                    // A manual check must say it failed; an automatic one stays quiet, because on a
                    // network that filters GitHub this would otherwise be a message on every launch.
                    if (force) _notice.value = "检查更新失败：${result.message}"
                }
            }
        }
    }

    fun skip(update: AvailableUpdate) {
        settings.updateSkippedVersion = update.versionName
        _state.value = UpdateUiState.AvailableSkipped(update)
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    /**
     * Downloads the update's APK and hands it to the system installer.
     *
     * The file is written to the app's private cache first, so it is never world-readable and is removed by
     * the system when space is needed. Only after it is complete and size-checked does the installer get
     * involved — a truncated APK installed from a partial download would look like a corrupt build.
     */
    fun downloadAndInstall(update: AvailableUpdate) {
        val asset = update.apk
        if (asset == null) {
            _state.value = UpdateUiState.Failed("这个版本没有提供安装包", update)
            return
        }
        // Re-checked here as well as when the release was parsed: this is the last point before bytes move.
        if (!UpdateRules.isTrustedDownloadUrl(asset.url)) {
            _state.value = UpdateUiState.Failed("安装包地址不可信，已停止下载", update)
            return
        }
        scope.launch {
            _state.value = UpdateUiState.Downloading(0, update)
            val file = withContext(Dispatchers.IO) { runCatching { fetch(asset, update) }.getOrNull() }
            if (file == null) {
                _state.value = UpdateUiState.Failed("下载失败，请稍后重试或打开下载页", update)
                return@launch
            }
            if (launchInstall(file)) {
                // The system installer is now in front and owns the rest. Dismissing here means returning
                // to the app does not show a stale "下载中" panel over a completed transfer.
                _state.value = UpdateUiState.Idle
            } else {
                _state.value = UpdateUiState.Failed("无法直接安装，请打开下载页手动安装", update)
            }
        }
    }

    /** Streams the APK to the cache directory, reporting progress as it goes. */
    private suspend fun fetch(asset: ReleaseAsset, update: AvailableUpdate): File? {
        val request = Request.Builder().url(asset.url).header("User-Agent", "YunYin/$currentVersion").build()
        // A dedicated subdirectory, because the FileProvider's declared paths grant access to exactly
        // `cacheDir/updates` and nothing else. A file written directly into cacheDir would be impossible
        // to share, which fails at install time rather than at download time.
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update-${asset.name.ifBlank { "app.apk" }}")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val total = if (asset.sizeBytes > 0) asset.sizeBytes else body.contentLength()
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    // `coroutineContext.isActive` rather than `isActive`: this is a plain suspend function,
                    // so there is no CoroutineScope receiver to read the flag from. Checking it is what
                    // makes the download stop when the caller's scope is cancelled.
                    while (coroutineContext.isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = UpdateUiState.Downloading(
                                ((written * 100) / total).toInt().coerceIn(0, 100), update,
                            )
                        } else {
                            // No length to divide by: report movement rather than a stuck 0%.
                            _state.value = UpdateUiState.Downloading(-1, update)
                        }
                    }
                }
            }
        }
        // A short file is a failed one; installing it would surface as a corrupt package.
        if (asset.sizeBytes > 0 && target.length() != asset.sizeBytes) {
            target.delete()
            return null
        }
        return target
    }

    /**
     * Hands the downloaded APK to the system's own installer UI.
     *
     * An `ACTION_VIEW` on a `content://` URI with the APK mime type, rather than a
     * `PackageInstaller` session. Two reasons, both learned from how this breaks in practice:
     *
     *  - **`file://` is refused outright** on Android 7+, and a file URI would also expose the app's
     *    private directory — hence [UpdateFileProvider] with `FLAG_GRANT_READ_URI_PERMISSION`.
     *  - **A commit with no result receiver is unreliable.** The session API is built for silent or
     *    streamed installs and is easy to get subtly wrong (closing the session can cancel the commit),
     *    and its behaviour varies by ROM. `ACTION_VIEW` hands the whole job — confirmation, progress,
     *    error reporting — to the platform installer, which is what a user expects to see anyway.
     *
     * Android shows its own "do you want to install this update?" dialog; there is no silent path, and
     * that is by design for something that replaces the running program. When the intent cannot be handled
     * — no installer, or installation from unknown sources is off — this returns false so the caller can
     * open the release page instead of dead-ending.
     */
    private fun launchInstall(file: File): Boolean = runCatching {
        val uri = UpdateFileProvider.uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse { false }

    private companion object {
        /** Once a day. GitHub allows 60 anonymous API calls an hour; this uses one. */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
