package com.yunyin.music.data.lxsource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Import, list, enable/disable and delete LX custom sources.
 *
 * ## The import sequence, and why each step is where it is
 *
 * 1. **Read** the picked file, with a size cap. A script is code the user selected from somewhere; an
 *    unbounded read is an out-of-memory kill on a bad file.
 * 2. **Parse the header comment** (pure, [LxScriptHeaderParser]). A file without `@name` is refused before any
 *    JavaScript runs — no engine is needed to know that a file is not a source.
 * 3. **Run it and wait for `inited`** ([LxJsRuntime]). Only here is the script's *declaration* known: which
 *    source keys it answers for, which actions and qualities each supports. LX's contract is explicit that any
 *    error before `inited` means the script failed to initialise, so a failure at this step is never a warning.
 * 4. **Validate the declaration** (pure, [LxInitedValidator]) — a protocol violation is refused now, rather
 *    than becoming a request the script cannot answer in phase 2.
 * 5. **Persist** the script file and its metadata, then apply the collision rule to decide whether the new
 *    source may start enabled.
 *
 * Steps 2 and 4 are pure functions on purpose: they are the parts that can be wrong in ways a device would not
 * show, so they are unit-tested without the engine.
 */
class LxSourceManager internal constructor(
    /** `null` in a JVM test that only exercises the engine-free half ([importScript]'s size/header checks). */
    private val appContext: Context?,
    private val store: LxSourceStore,
    private val log: LxSourceLog,
    /** Injected so the URL policy/fetch outcomes are unit-testable without a network. */
    private val fetcher: LxSourceFetcher = OkHttpLxSourceFetcher(log),
) {

    /** Production entry point: the file-backed store and log both live under `filesDir/lxsource`. */
    constructor(context: Context) : this(
        appContext = context.applicationContext,
        store = LxSourceStore(context),
        log = LxSourceLog(context),
    )

    /** See [revision]. */
    private val revisionCounter = java.util.concurrent.atomic.AtomicLong(0)

    fun list(): List<LxImportedSource> = store.load()

    /**
     * Bumped whenever the set of imported sources or their enabled flags changes.
     *
     * The playback path asks "which source is enabled for `wy`" on every track load; reading and parsing the
     * sidecar each time would be silly, and a timestamp-based cache would either go stale after a toggle or miss
     * it. A revision that only changes on a mutation makes the cache exact.
     */
    fun revision(): Long = revisionCounter.get()

    fun lastError(): String? = log.readLast()

    /** The script text for an imported source; phase 2 executes it, phase 1 only lists it. */
    fun readScript(source: LxImportedSource): String? = store.readScript(source.id)

    /**
     * Reads a picked document and imports it.
     *
     * `OpenDocument` rather than `GetContent`: the contract returns a stable `content://` URI the app can read
     * without a storage permission, which is the same arrangement the photo pickers use.
     */
    suspend fun import(uri: Uri): LxImportResult = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext LxImportResult.Failure("无法读取所选文件")
        val scriptText = try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext LxImportResult.Failure("无法读取所选文件")
            val bytes = stream.use(::readBounded)
                ?: return@withContext LxImportResult.Failure(
                    "脚本文件过大（上限 ${MAX_SCRIPT_BYTES / 1024} KB）",
                )
            String(bytes, Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.record("读取脚本失败", e.stackTraceToString())
            return@withContext LxImportResult.Failure("无法读取所选文件：${e.message ?: e.javaClass.simpleName}")
        }
        importScript(scriptText)
    }

    /**
     * Imports a source downloaded from [rawUrl], going through exactly the same validation as a local file.
     *
     * The URL is only a delivery mechanism: policy → download → [importScript]. Nothing about a URL can skip the
     * header parse, the Listen1/Node refusal, the engine run or the `inited` validation.
     */
    suspend fun importFromUrl(rawUrl: String): LxImportResult {
        // Every public import entry point is a UI boundary: it must **return** an outcome for anything thrown on
        // the way (engine load errors such as UnsatisfiedLinkError are Errors, not Exceptions). A crash here would
        // take the whole app down from a settings tap, which is never acceptable for untrusted input.
        return try {
            importFromUrlUnsafe(rawUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.record("从链接导入异常", "url=${LxSourceUrlPolicy.redact(rawUrl)}\n${t.stackTraceToString()}")
            LxImportResult.Failure("导入时发生内部错误：${summaryOf(t)}", t.stackTraceToString())
        }
    }

    private suspend fun importFromUrlUnsafe(rawUrl: String): LxImportResult {
        val original = when (val verdict = LxSourceUrlPolicy.validate(rawUrl)) {
            is LxSourceUrlPolicy.Verdict.Rejected -> {
                log.record("从链接导入被拒绝", "url=${LxSourceUrlPolicy.redact(rawUrl)}\n${verdict.message}")
                return LxImportResult.Failure(verdict.message)
            }
            is LxSourceUrlPolicy.Verdict.Allowed -> verdict.url
        }
        return when (val chain = fetchWithFallback(original)) {
            is LxSourceFetchChain.Failure -> {
                log.record("从链接导入失败", "url=${LxSourceUrlPolicy.redact(rawUrl)}\n${chain.detail}")
                LxImportResult.Failure(chain.message, chain.detail)
            }
            is LxSourceFetchChain.Ok -> {
                // The **pasted** URL is persisted as the origin, not the mirror: that is what 更新 re-fetches (and
                // what the user recognizes), and it lets a later update pick a different working mirror again.
                when (val imported = importScript(chain.text, originUrl = original)) {
                    is LxImportResult.Success -> imported.copy(viaMirror = chain.candidate.takeIf { it.mirrored }?.label)
                    is LxImportResult.Failure -> imported
                }
            }
        }
    }

    /**
     * Re-downloads a URL-sourced script and replaces it atomically, keeping its enabled state.
     *
     * Metadata is rewritten in one write and the old script file is deleted only afterwards (see
     * [LxSourceStore.addOrReplace]); a reload that fails validates nothing and leaves the old source untouched.
     */
    suspend fun refreshFromUrl(source: LxImportedSource): LxImportResult {
        return try {
            val origin = source.originUrl
                ?: return LxImportResult.Failure("这个音源不是从链接导入的，无法重新拉取")
            when (val chain = fetchWithFallback(origin)) {
                is LxSourceFetchChain.Failure -> {
                    log.record("重新拉取音源失败", "url=${LxSourceUrlPolicy.redact(origin)}\n${chain.detail}")
                    LxImportResult.Failure(chain.message, chain.detail)
                }
                is LxSourceFetchChain.Ok ->
                    // 更新 keeps the original origin and may succeed through a *different* mirror this time.
                    when (val imported = importScript(
                        chain.text,
                        originUrl = origin,
                        keepEnabledFrom = source,
                    )) {
                        is LxImportResult.Success ->
                            imported.copy(viaMirror = chain.candidate.takeIf { it.mirrored }?.label)
                        is LxImportResult.Failure -> imported
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.record("重新拉取音源异常", "url=${LxSourceUrlPolicy.redact(source.originUrl.orEmpty())}\n${t.stackTraceToString()}")
            LxImportResult.Failure("导入时发生内部错误：${summaryOf(t)}", t.stackTraceToString())
        }
    }

    /**
     * Downloads [rawUrl] trying the original address first, then known GitHub mirrors (see [LxSourceMirrorRules]).
     *
     * Every candidate goes through [LxSourceFetcher], which applies [LxSourceUrlPolicy] (scheme, credentials,
     * private-address DNS/redirect checks) and the 4 MiB content checks — the mirror is an **origin rewrite**, not a
     * policy exemption. The whole chain has a [FETCH_TOTAL_DEADLINE_MS] budget, checked across attempts (the fetcher's
     * blocking call is interruptible, so a stalled attempt is aborted at the deadline rather than allowed to run on).
     */
    internal suspend fun fetchWithFallback(rawUrl: String): LxSourceFetchChain {
        val candidates = LxSourceMirrorRules.candidates(rawUrl)
        val attempts = mutableListOf<LxFetchAttempt>()
        val result = withTimeoutOrNull(FETCH_TOTAL_DEADLINE_MS) {
            for (candidate in candidates) {
                when (val fetched = fetcher.fetch(candidate.url)) {
                    is LxSourceFetchResult.Ok ->
                        return@withTimeoutOrNull LxSourceFetchChain.Ok(fetched.text, fetched.bytes, candidate)
                    is LxSourceFetchResult.Failure -> attempts += LxFetchAttempt(candidate.label, fetched)
                }
            }
            null
        }
        if (result != null) return result
        // Fewer attempts than candidates means the deadline stopped the chain mid-flight.
        val timedOut = attempts.size < candidates.size
        val (message, detail) = describeFetchFailure(attempts, timedOut, (FETCH_TOTAL_DEADLINE_MS / 1000).toInt())
        return LxSourceFetchChain.Failure(message, detail)
    }

    /** One line for the sheet; the stack goes to the log. */
    private fun summaryOf(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull().orEmpty().take(MAX_MESSAGE_LENGTH)

    /**
     * Imports [scriptText], which has already been read.
     *
     * Split from [import] so the engine half is reachable without a document provider, and so the file IO and
     * the JavaScript never share a code path.
     *
     * @param originUrl set for a URL import, persisted so the list can show where the script came from.
     * @param keepEnabledFrom the previous entry a 重新拉取 replaces (keeps its enabled state).
     */
    suspend fun importScript(
        scriptText: String,
        originUrl: String? = null,
        keepEnabledFrom: LxImportedSource? = null,
    ): LxImportResult = try {
        importScriptUnsafe(scriptText, originUrl, keepEnabledFrom)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Same boundary as the URL path: an engine `Error` must become a message, not a process death.
        log.record("导入音源异常", "url=${LxSourceUrlPolicy.redact(originUrl.orEmpty())}\n${t.stackTraceToString()}")
        LxImportResult.Failure("导入时发生内部错误：${summaryOf(t)}", t.stackTraceToString())
    }

    private suspend fun importScriptUnsafe(
        scriptText: String,
        originUrl: String?,
        keepEnabledFrom: LxImportedSource?,
    ): LxImportResult {
        val text = scriptText.removePrefix("\uFEFF")
        if (text.isBlank()) return LxImportResult.Failure("脚本是空的")
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > MAX_SCRIPT_BYTES) {
            return LxImportResult.Failure("脚本文件过大（上限 ${MAX_SCRIPT_BYTES / 1024} KB）")
        }

        val header = when (val parsed = LxScriptHeaderParser.parse(text)) {
            is LxScriptHeaderParser.Result.Ok -> parsed.header
            is LxScriptHeaderParser.Result.Error -> {
                // A Listen1/Node bundle fails the header parse too, but "missing header comment" is a misleading
                // explanation for it: it is a different ecosystem that needs a Node runtime. Name it instead, and
                // say what the user does not need in order to play QQ Music.
                val nodeBundle = LxScriptFormat.detectNodeBundle(text)
                if (nodeBundle != null) {
                    val detail = "拒绝导入：Listen1/Node 插件包（需要 Node.js，本应用不支持）\n" +
                        nodeBundle.describe()
                    log.record("拒绝导入：Listen1/Node 插件包", detail)
                    return LxImportResult.Failure(LxScriptFormat.NODE_BUNDLE_MESSAGE, detail)
                }
                return LxImportResult.Failure(parsed.message).also {
                    if (originUrl != null) {
                        log.record(
                            "从链接导入失败",
                            "url=${LxSourceUrlPolicy.redact(originUrl)}\n${parsed.message}",
                        )
                    }
                }
            }
        }

        val runtime = LxJsRuntime(log)
        val validation = try {
            runtime.validateScript(text, header)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.record("执行脚本失败", e.stackTraceToString())
            val message = if (originUrl == null) {
                "执行脚本失败：${e.message ?: e.javaClass.simpleName}"
            } else {
                "脚本报错：${(e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull().orEmpty()}"
            }
            return LxImportResult.Failure(message, e.stackTraceToString())
        }

        val descriptors = when (validation) {
            is LxInitedValidator.Result.Ok -> validation.sources
            is LxInitedValidator.Result.Error -> {
                val message = if (originUrl == null) {
                    validation.message
                } else {
                    urlImportScriptFailure(validation.message)
                }
                log.record(
                    "从链接导入失败",
                    "url=${LxSourceUrlPolicy.redact(originUrl.orEmpty())}\n${validation.message}\n" +
                        validation.detail.orEmpty(),
                )
                return LxImportResult.Failure(message, validation.detail)
            }
        }

        return try {
            val success = store.addOrReplace(
                header = header,
                descriptors = descriptors,
                scriptText = text,
                originUrl = originUrl,
                keepEnabledFrom = keepEnabledFrom,
            )
            // A working import supersedes the previous failure, so the "copy error log" row does not linger
            // beside a source that just imported cleanly.
            log.clear()
            revisionCounter.incrementAndGet()
            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.record("保存音源失败", e.stackTraceToString())
            LxImportResult.Failure("保存音源失败：${e.message ?: e.javaClass.simpleName}", e.stackTraceToString())
        }
    }

    /**
     * Enables or disables a source, enforcing the one-enabled-source-per-key rule.
     *
     * See [findLxSourceKeyConflict]: two sources claiming `wy` cannot both be on, and enabling is the moment the
     * ambiguity would be created, so that is where it is refused.
     */
    fun setEnabled(id: String, enabled: Boolean): LxToggleResult =
        store.setEnabled(id, enabled).also { if (it is LxToggleResult.Ok) revisionCounter.incrementAndGet() }

    fun delete(id: String): Boolean =
        store.delete(id).also { if (it) revisionCounter.incrementAndGet() }

    /**
     * Maps the engine's failure to the 从链接导入 message list.
     *
     * A timeout is its own cause ("初始化超时"); any other run failure is "脚本报错" with the script's first line as
     * the summary, so the sheet has a specific line and the log keeps the stack. Local-file imports keep their
     * original wording.
     */
    private fun urlImportScriptFailure(raw: String): String {
        val firstLine = raw.lineSequence().firstOrNull().orEmpty().trim()
        return when {
            raw.contains("超时") || raw.contains("timeout", ignoreCase = true) ->
                "初始化超时：脚本未在限定时间内发送 inited 事件"
            firstLine.startsWith("脚本初始化失败：") ->
                "脚本报错：${firstLine.removePrefix("脚本初始化失败：").take(MAX_MESSAGE_LENGTH)}"
            else -> "脚本报错：${firstLine.take(MAX_MESSAGE_LENGTH)}"
        }
    }

    /** Reads at most [MAX_SCRIPT_BYTES] + 1 bytes; `null` means "too large" rather than "empty". */
    private fun readBounded(input: InputStream): ByteArray? {        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_SCRIPT_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        /**
         * 4 MiB. LX sources in the wild are tens to hundreds of kilobytes (the common provider sources are
         * 500–900 KB), and an aggregator that bundles several providers is larger still. The cap is a guard
         * against choosing the wrong file (a video, a log) — it has to sit comfortably above real sources, or a
         * valid import would be refused for its size instead of being run.
         */
        const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024

        /** Cap on the one-line summary shown in the import result. */
        const val MAX_MESSAGE_LENGTH = 160

        /**
         * Whole-chain budget for one import/update: 50 s.
         *
         * The original attempt runs first and each attempt keeps the fetcher's 10 s connect / 20 s read / 30 s call
         * timeouts, so three sequential attempts would otherwise be able to hold a tap for ~90 s. The budget is
         * enforced *around* the chain and the blocking call is interruptible, so a stalled attempt is cut at the
         * deadline instead of overrunning it.
         */
        const val FETCH_TOTAL_DEADLINE_MS = 50_000L
    }
}

/** One attempted download in [LxSourceManager.fetchWithFallback]. */
internal data class LxFetchAttempt(val label: String, val failure: LxSourceFetchResult.Failure)

/** Outcome of the original-plus-mirrors download chain. */
internal sealed interface LxSourceFetchChain {

    data class Ok(
        val text: String,
        val bytes: Int,
        /** The candidate that succeeded; [LxSourceMirrorRules.Candidate.mirrored] tells the UI to say "已通过镜像". */
        val candidate: LxSourceMirrorRules.Candidate,
    ) : LxSourceFetchChain

    data class Failure(val message: String, val detail: String) : LxSourceFetchChain
}

/**
 * Builds the failure line for the whole chain.
 *
 * One attempt keeps its specific message untouched (e.g. `HTTP 404：链接不存在`, `网络错误：无法解析域名 …`).
 * Several attempts are listed together, naming each address, because that is the diagnosis: if the original is
 * timing out and jsDelivr resets too, it is the user's network path — and the line says so instead of showing a bare
 * "网络错误". A deadline stop adds that fact.
 */
internal fun describeFetchFailure(
    attempts: List<LxFetchAttempt>,
    timedOut: Boolean,
    totalSeconds: Int,
): Pair<String, String> {
    if (attempts.size == 1 && !timedOut) {
        return attempts.single().failure.message to (attempts.single().failure.detail ?: "")
    }
    fun short(message: String): String = message.removePrefix("网络错误：").removePrefix("网络不可达：")
    val parts = attempts.map { "${it.label}：${short(it.failure.message)}" }
    val allNetwork = attempts.isNotEmpty() && attempts.all { it.failure.message.startsWith("网络错误：") }
    val prefix = if (allNetwork) "网络不可达：" else "导入失败："
    val timeoutPart = if (timedOut) "总超时（${totalSeconds} 秒）" else null
    val reasons = (parts + listOfNotNull(timeoutPart)).joinToString("；").ifEmpty { "下载失败" }
    val message = buildString {
        append(prefix).append(reasons)
        if (allNetwork || timedOut) append("。请检查网络，或换用其它镜像链接")
    }
    val detail = buildString {
        append("chain attempts=").append(attempts.size).append(" timedOut=").append(timedOut).append('\n')
        attempts.forEach { attempt ->
            append("attempt=").append(attempt.label).append(" -> ").append(attempt.failure.message).append('\n')
            attempt.failure.detail?.lines()?.forEach { line -> append("  ").append(line).append('\n') }
        }
    }.trimEnd()
    return message.take(300) to detail
}
