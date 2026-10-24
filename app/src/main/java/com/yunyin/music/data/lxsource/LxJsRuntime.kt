// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.Closeable

/**
 * The bridge between a custom-source script and the host.
 *
 * ## The engine, and why this one
 *
 * A source is JavaScript built around `async`/`await` and Promises: the request handler *must* return a
 * Promise, and initialisation is usually `await lx.request(…); lx.send('inited', …)`. So the engine must drain
 * QuickJS's microtask queue after evaluation. `app.cash.quickjs:quickjs-android:0.9.2` resolves but cannot do
 * that — its `Context::eval` only calls `JS_Eval` and never `JS_ExecutePendingJob` (verified against the
 * published sources) — so a Promise never settles and every `await` hangs. Rhino has no Promise at all, and
 * J2V8 is an order of magnitude larger. `io.github.dokar3:quickjs-kt` exposes a **suspending** `evaluate()`
 * that awaits pending jobs, `asyncFunction` bindings for `lx.request`, and an evaluation timeout with
 * interruption — the three properties this feature needs.
 *
 * ## Threading
 *
 * The `QuickJs` instance is confined to [Dispatchers.Default] and the library serialises all access to it. The
 * host functions it calls back into are either pure JVM code (utilities) or suspend (HTTP), so no callback ever
 * touches the main thread. Android UI state is only written by whoever awaited [validateScript].
 *
 * ## Timeouts
 *
 * Two layers, because a broken script fails in two different ways. `evaluationTimeoutMillis` interrupts a
 * synchronous infinite loop (`while(true){}`). The wall-clock race in [runScriptUntilInited] covers the other
 * shape: a script that awaits a host call that never returns. Both produce the same user-facing message, and
 * neither can hang the import.
 *
 * ## Lifecycle
 *
 * [validateScript] is one-shot: it opens an engine, runs the script, waits for `inited`, validates the payload
 * and closes. Phase 2 will keep an instance open through [open] and call [request] per playback; the entry
 * point exists and is wired to the same host surface, but nothing in this phase invokes it.
 */
class LxJsRuntime(private val log: LxSourceLog) : Closeable {

    private val http = LxHttpBridge(log)

    private var quickJs: QuickJs? = null
    private var initedPayload: CompletableDeferred<String>? = null

    /**
     * The diagnostic record of the lookup currently being dispatched, or null for an import-time run.
     *
     * The host functions are installed once per engine, but a trace is per lookup, so the request binding reads
     * this field. Requests on one runtime are serialised by the playback pool's mutex, so there is one at a time.
     */
    @Volatile
    private var activeTrace: LxLookupTrace? = null

    /**
     * Opens an engine, runs [scriptText] and waits for its `inited` event, leaving the runtime **open** on
     * success so [request] can be called afterwards.
     *
     * This is phase 2's entry point; [validateScript] wraps it for the import path. On failure the engine is
     * closed before returning, because a context whose script failed to initialise can never answer a request.
     */
    suspend fun open(
        scriptText: String,
        header: LxScriptHeader,
        timeoutMs: Long = DEFAULT_INIT_TIMEOUT_MS,
    ): LxInitedValidator.Result {
        val result = try {
            openEngine(header, scriptText, timeoutMs)
            runScriptUntilInited(scriptText, header, timeoutMs)
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Throwable) {
            log.record("启动 JS 引擎失败", e.stackTraceToString())
            LxInitedValidator.Result.Error(
                message = "内置 JS 引擎启动失败：${e.message ?: e.javaClass.simpleName}",
                detail = e.stackTraceToString(),
            )
        }
        if (result is LxInitedValidator.Result.Error) close()
        return result
    }

    /**
     * Imports one script: runs it, waits for `inited`, and validates what it declared.
     *
     * The engine is always closed, including on failure: a source that fails to initialise must not leave a
     * QuickJS context (and its native memory) behind.
     */
    suspend fun validateScript(
        scriptText: String,
        header: LxScriptHeader,
        timeoutMs: Long = DEFAULT_INIT_TIMEOUT_MS,
    ): LxInitedValidator.Result {
        return try {
            open(scriptText, header, timeoutMs)
        } finally {
            close()
        }
    }

    /**
     * Dispatches an event to the script's registered handler and returns its result as JSON.
     *
     * This is the `request` half of the protocol and the entry point phase 2 uses for `musicUrl`. The envelope
     * keeps script errors out of the engine: a rejected handler is thrown as [LxSourceRequestException]
     * carrying the JS stack, not as an engine failure.
     *
     * Timers scheduled by the handler are cleared when the run ends, and the whole dispatch is bounded by
     * [timeoutMs] — a source that never settles fails with a specific error instead of holding the caller.
     */
    suspend fun request(
        eventName: String,
        payloadJson: String,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        trace: LxLookupTrace? = null,
    ): String {
        val engine = quickJs ?: throw IllegalStateException("LxJsRuntime is not open")
        activeTrace = trace
        return try {
            engine.evaluate<Any?>(
                "globalThis.__lxPendingEvent = ${JSONObject.quote(eventName)};" +
                    "globalThis.__lxPendingPayload = ${JSONObject.quote(payloadJson)};",
                filename = "lx-dispatch.js",
            )
            val result = withTimeout(timeoutMs) {
                engine.evaluate<String>("await __lxDispatchPending()", filename = "lx-$eventName.js")
            }
            val json = JSONObject(result)
            if (!json.optBoolean("ok")) {
                throw LxSourceRequestException(
                    message = json.optString("error", "脚本请求失败"),
                    jsStack = json.optString("stack").takeIf { it.isNotBlank() },
                )
            }
            val value = json.opt("value")
            if (value == null || value == JSONObject.NULL) "null"
            else if (value is String) JSONObject.quote(value) else value.toString()
        } catch (e: TimeoutCancellationException) {
            log.record("脚本处理 $eventName 超时", "eventName=$eventName timeoutMs=$timeoutMs")
            throw LxSourceRequestException(
                message = "脚本处理 $eventName 超时（${timeoutMs / 1000} 秒）",
                jsStack = null,
            )
        } finally {
            activeTrace = null
            clearTimers()
        }
    }

    /**
     * Invalidates every timer the script scheduled.
     *
     * Called when a run ends so an interval cannot keep a dead run's job queue busy. Suspending because the
     * clear is a JS evaluation; best-effort because it must not turn a successful result into a failure.
     */
    suspend fun clearTimers() {
        val engine = quickJs ?: return
        runCatching { engine.evaluate<Any?>("__lxClearTimers()", filename = "lx-timers.js") }
    }

    override fun close() {
        initedPayload?.cancel()
        initedPayload = null
        val engine = quickJs
        quickJs = null
        if (engine != null) runCatching { engine.close() }
    }

    // ---------------------------------------------------------------- engine setup

    private suspend fun openEngine(header: LxScriptHeader, scriptText: String, timeoutMs: Long) {
        val engine = QuickJs.create(Dispatchers.Default)
        quickJs = engine
        // Backstop for a synchronous spin; the wall-clock race below is the primary limit.
        engine.evaluationTimeoutMillis = timeoutMs
        val inited = CompletableDeferred<String>()
        initedPayload = inited
        installHostFunctions(engine, header, scriptText, inited)
        engine.evaluate<Any?>(LX_BOOTSTRAP_SCRIPT, filename = "lx-bootstrap.js")
    }

    private fun installHostFunctions(
        engine: QuickJs,
        header: LxScriptHeader,
        rawScript: String,
        inited: CompletableDeferred<String>,
    ) {
        val scriptInfo = JSONObject()
            .put("name", header.name)
            .put("description", header.description ?: "")
            .put("version", header.version ?: "")
            .put("author", header.author ?: "")
            .put("homepage", header.homepage ?: "")
            .put("rawScript", rawScript)
        val infoJson = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("scriptInfo", scriptInfo)
            .toString()

        // Only primitives cross this boundary; the JS bootstrap assembles `globalThis.lx` out of these.
        engine.function("__lxScriptInfo") { infoJson }

        engine.function("__lxInited") { args: Array<Any?> ->
            val payload = args.getOrNull(0) as? String
            when {
                payload == null -> "inited 事件缺少数据"
                // First `inited` wins: a second one is a script bug, and the JS side turns this into the
                // documented rejection ("Script is inited") instead of masking it.
                inited.isCompleted -> "Script is inited"
                else -> {
                    inited.complete(payload)
                    ""
                }
            }
        }

        engine.function("__lxLog") { args: Array<Any?> ->
            val level = args.getOrNull(0) as? String ?: "log"
            val message = args.getOrNull(1) as? String ?: ""
            Log.d(TAG, "[$level] $message")
            Unit
        }

        engine.function("__lxMd5") { args: Array<Any?> ->
            LxHostUtilities.md5Hex(args.getOrNull(0) as? String ?: "")
        }
        engine.function("__lxRandomBytes") { args: Array<Any?> ->
            LxHostUtilities.randomHex((args.getOrNull(0) as? Number)?.toInt() ?: 0)
        }
        engine.function("__lxUtf8ToHex") { args: Array<Any?> ->
            LxHostUtilities.utf8ToHex(args.getOrNull(0) as? String ?: "")
        }
        engine.function("__lxHexToUtf8") { args: Array<Any?> ->
            LxHostUtilities.hexToUtf8(args.getOrNull(0) as? String ?: "")
        }
        engine.function("__lxBase64ToHex") { args: Array<Any?> ->
            LxHostUtilities.base64ToHex(args.getOrNull(0) as? String ?: "")
        }
        engine.function("__lxHexToBase64") { args: Array<Any?> ->
            LxHostUtilities.hexToBase64(args.getOrNull(0) as? String ?: "")
        }
        // zlib failures are values, not engine failures: the JS side wraps these in callHostEnvelope so a
        // source's `try/catch` (or `.catch` on the returned promise) sees a normal Error.
        engine.function("__lxZlibInflate") { args: Array<Any?> ->
            envelope { LxHostUtilities.inflate(args.getOrNull(0) as? String ?: "") }
        }
        engine.function("__lxZlibDeflate") { args: Array<Any?> ->
            envelope { LxHostUtilities.deflate(args.getOrNull(0) as? String ?: "") }
        }

        // ---- lx.utils.crypto: the mobile doc marks AES as partial (128-bit CBC/ECB) and the desktop host
        // implements it as Node's createCipheriv; RSA matches LX's RSA_NO_PADDING over zero-padded input. ----
        engine.function("__lxAesEncrypt") { args: Array<Any?> ->
            envelope {
                LxHostUtilities.aesEncrypt(
                    mode = args.getOrNull(0) as? String ?: "",
                    keyHex = args.getOrNull(1) as? String ?: "",
                    ivHex = args.getOrNull(2) as? String ?: "",
                    dataHex = args.getOrNull(3) as? String ?: "",
                )
            }
        }
        engine.function("__lxRsaEncrypt") { args: Array<Any?> ->
            envelope {
                LxHostUtilities.rsaEncrypt(
                    dataHex = args.getOrNull(0) as? String ?: "",
                    publicKeyPem = args.getOrNull(1) as? String ?: "",
                )
            }
        }

        // ---- timers -------------------------------------------------------------------------------
        // QuickJS has no event loop, so a timer is a host-suspended job: `delay` suspends this asyncFunction
        // while the library keeps draining the JS job queue, which is what lets a source `await` a delay. The
        // JS side owns the timer registry (Kotlin cannot hold a JS function); clearing invalidates the
        // generation, so a woken sleeper becomes a no-op instead of firing a cancelled callback.
        engine.asyncFunction("__lxTimerSleep") { args: Array<Any?> ->
            val waitMs = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
            delay(waitMs.coerceAtLeast(0L))
            Unit
        }

        engine.function("__lxCancel") { args: Array<Any?> ->
            http.cancel((args.getOrNull(0) as? Number)?.toLong() ?: -1L)
            Unit
        }
        engine.asyncFunction("__lxRequest") { args: Array<Any?> ->
            http.execute(
                url = args.getOrNull(0) as? String ?: "",
                optionsJson = args.getOrNull(1) as? String ?: "{}",
                requestId = (args.getOrNull(2) as? Number)?.toLong() ?: -1L,
                trace = activeTrace,
            )
        }
    }

    /**
     * Runs a host call and returns it as a JSON envelope.
     *
     * A Kotlin exception thrown straight through a binding surfaces as an engine failure, which a script cannot
     * catch; an envelope keeps every refusal a value, so `try { lx.utils.crypto.aesEncrypt(...) } catch (e)` and
     * `zlib.inflate(...).catch(...)` behave the way a source written against LX expects.
     */
    private fun envelope(block: () -> String): String =
        try {
            JSONObject().put("ok", true).put("value", block()).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName)
                .toString()
        }

    // ---------------------------------------------------------------- the inited handshake

    /**
     * Runs the script and waits for its `inited` event.
     *
     * The wait is a race between three things: the script finishing (which may be a plain return, a JS
     * exception, or a timeout), the `inited` callback firing, and the wall clock. Whichever happens first
     * decides, except that an already-delivered `inited` always wins — LX's contract is that a failure
     * *before* `inited` fails the import, and a source is allowed to be busy afterwards.
     */
    private suspend fun runScriptUntilInited(
        scriptText: String,
        header: LxScriptHeader,
        timeoutMs: Long,
    ): LxInitedValidator.Result {
        val engine = quickJs ?: return LxInitedValidator.Result.Error("JS 引擎未启动")
        val inited = initedPayload ?: return LxInitedValidator.Result.Error("JS 引擎未启动")

        val outcomes = Channel<InitOutcome>(Channel.CONFLATED)
        val outcome = coroutineScope {
            val evalJob = launch {
                var failure: Throwable? = null
                try {
                    engine.evaluate<Any?>(scriptText, filename = "${header.name}.js")
                } catch (e: Throwable) {
                    // Cancellation here is this host stopping the evaluation, not a script error; it is
                    // recorded and mapped below rather than escaping the bridge.
                    failure = e
                }
                outcomes.trySend(InitOutcome.Finished(failure))
            }
            val waitJob = launch {
                outcomes.trySend(InitOutcome.Inited(inited.await()))
            }
            val received = try {
                withTimeoutOrNull(timeoutMs) { outcomes.receive() }
            } finally {
                evalJob.cancel()
                waitJob.cancel()
                outcomes.close()
            }
            received
        }

        if (inited.isCompleted) {
            return LxInitedValidator.validate(inited.await())
        }

        return when (val result = outcome) {
            is InitOutcome.Inited -> LxInitedValidator.validate(result.payload)
            is InitOutcome.Finished -> when (val failure = result.failure) {
                null -> noInitedFailure()
                is QuickJsInterruptedException -> timeoutFailure(timeoutMs, failure.stackTraceToString())
                is QuickJsException -> scriptFailure(failure)
                else -> scriptFailure(failure)
            }
            null -> timeoutFailure(timeoutMs, detail = "脚本在 $timeoutMs ms 内没有结束，也没有发送 inited 事件")
        }
    }

    private fun noInitedFailure(): LxInitedValidator.Result.Error {
        val message = "脚本执行完毕但没有发送 inited 事件（脚本需要调用 lx.send(lx.EVENT_NAMES.inited, …)）"
        log.record("脚本未发送 inited", message)
        return LxInitedValidator.Result.Error(message)
    }

    private fun timeoutFailure(timeoutMs: Long, detail: String): LxInitedValidator.Result.Error {
        val message = "脚本初始化超时（${timeoutMs / 1000} 秒内没有收到 inited 事件）"
        log.record("脚本初始化超时", message + "\n" + detail)
        return LxInitedValidator.Result.Error(message, detail)
    }

    private fun scriptFailure(error: Throwable): LxInitedValidator.Result.Error {
        val detail = buildString {
            append(error.javaClass.simpleName).append(": ").append(error.message)
            if (error is QuickJsException) {
                append("\nfile=").append(error.fileName)
                append(" line=").append(error.lineNumber)
                append(" column=").append(error.columnNumber)
                error.stack?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            } else {
                append("\n").append(error.stackTraceToString())
            }
        }
        log.record("脚本初始化失败", detail)
        val firstLine = (error.message ?: error.javaClass.simpleName)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .ifBlank { error.javaClass.simpleName }
            .take(MAX_USER_MESSAGE_LENGTH)
        return LxInitedValidator.Result.Error("脚本初始化失败：$firstLine", detail)
    }

    private sealed interface InitOutcome {
        data class Inited(val payload: String) : InitOutcome

        data class Finished(val failure: Throwable?) : InitOutcome
    }

    companion object {
        private const val TAG = "YunYin/LxSource"

        /**
         * The custom-source API version this host implements.
         *
         * `2.0.0` is what LX itself reports (`preload.js` exposes `version: '2.0.0'`); scripts feature-detect on
         * it, so it must be the real API version rather than a release number.
         */
        const val API_VERSION = "2.0.0"

        /**
         * How long a script may take to send `inited`.
         *
         * Long enough for the common pattern of one configuration request during init, short enough that a
         * broken source cannot hold the import screen. The UI shows a progress line while this runs.
         */
        const val DEFAULT_INIT_TIMEOUT_MS = 8_000L

        /**
         * How long a `musicUrl` handler may take.
         *
         * Generous because a source may make several API calls (and delay between retries), but finite: a
         * source that never settles must not look like a track that never loads. The playback resolver wraps
         * this in its own, shorter, cancellation so a stuck lookup cannot stall the player for this long.
         */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        private const val MAX_USER_MESSAGE_LENGTH = 160
    }
}

/** A script's handler rejected, or returned an error envelope. Carries the JS stack for the log. */
class LxSourceRequestException(message: String, val jsStack: String?) : Exception(message)
