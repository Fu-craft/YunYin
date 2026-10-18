package com.yunyin.music.data.lxsource

/**
 * The JavaScript half of the `globalThis.lx` host API.
 *
 * ## Why the API is built in JS instead of assembled from Kotlin bindings
 *
 * `lx.on(name, handler)` has to **keep a JavaScript function** and call it later, and QuickJS's Kotlin
 * binding cannot hand a JS function back to Kotlin as a callable value. So the split is: Kotlin installs flat
 * globals (`__lxRequest`, `__lxMd5`, `__lxAesEncrypt`, `__lxTimerSleep`, …) that only ever receive and return
 * **strings and numbers**, and this script assembles the documented object out of them, keeping handlers and
 * timers in JS maps. Nothing but primitives crosses the language boundary.
 *
 * ## Semantics are taken from LX's own host, not from the prose doc
 *
 * The authoritative behaviour is LX desktop's `src/main/modules/userApi/renderer/preload.js`:
 *
 *  - `lx.version` is the **custom-source API version, `'2.0.0'`** — scripts feature-detect against it;
 *  - `lx.on` / `lx.send` return Promises and reject unsupported event names, and `inited` may be sent once;
 *  - `lx.request`'s `resp` is `{ statusCode, statusMessage, headers, bytes, raw, body }`, where **`body` is the
 *    JSON-parsed object when the response is JSON** (a plain string otherwise) — sources written against LX do
 *    `resp.body.url`, so returning a raw string breaks them;
 *  - `utils.crypto.randomBytes(size)` returns a **Buffer**, not a string (`randomBytes(16).map(...)` is common);
 *  - `utils.crypto.rsaEncrypt(buffer, key)` is `RSA_NO_PADDING` over a zero-left-padded buffer, not PKCS#1 v1.5;
 *  - `utils.buffer.from` / `bufToString` / `md5` behave as documented.
 *
 * The mobile doc marks AES as partial (`aes-128-cbc`, `aes-128-ecb`), zlib as desktop-only, and
 * `setTimeout`/`clearTimeout` as the only available host timers. This host is a superset: AES also accepts
 * 192/256-bit keys, zlib is implemented, and `setInterval`/`clearInterval`/`queueMicrotask` exist as well —
 * each is documented here so the difference from LX mobile is explicit rather than accidental.
 *
 * ## Timers without an event loop
 *
 * QuickJS has none, so a timer is a **host-suspended job**: `__lxTimerSleep(ms)` is a suspending host function,
 * and the library keeps draining QuickJS's job queue while it is pending. That is the timer pump — the same
 * mechanism that lets an `async` source `await` an `lx.request`, applied to time. A generation counter makes
 * `__lxClearTimers()` (called when a run ends) cancel every pending timer at once without touching JS jobs it
 * cannot cancel: a woken sleeper sees the generation changed and does nothing.
 *
 * The text is compiled as-is; `LxJsRuntime` evaluates it once, before the imported script.
 */
internal const val LX_BOOTSTRAP_SCRIPT = """
(function () {
  'use strict';

  var host = {
    log: globalThis.__lxLog,
    scriptInfo: globalThis.__lxScriptInfo,
    inited: globalThis.__lxInited,
    md5: globalThis.__lxMd5,
    randomBytes: globalThis.__lxRandomBytes,
    utf8ToHex: globalThis.__lxUtf8ToHex,
    hexToUtf8: globalThis.__lxHexToUtf8,
    base64ToHex: globalThis.__lxBase64ToHex,
    hexToBase64: globalThis.__lxHexToBase64,
    zlibInflate: globalThis.__lxZlibInflate,
    zlibDeflate: globalThis.__lxZlibDeflate,
    aesEncrypt: globalThis.__lxAesEncrypt,
    rsaEncrypt: globalThis.__lxRsaEncrypt,
    request: globalThis.__lxRequest,
    cancel: globalThis.__lxCancel,
    timerSleep: globalThis.__lxTimerSleep
  };

  // ---- console ---------------------------------------------------------------------------------
  // QuickJS has no console, but the documented example script calls console.log, so a missing one would
  // turn a working source into "ReferenceError: console is not defined" during init.

  function stringifyLogArgs(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var value = args[i];
      if (typeof value === 'string') {
        parts.push(value);
      } else {
        try { parts.push(JSON.stringify(value)); } catch (e) { parts.push(String(value)); }
      }
    }
    return parts.join(' ');
  }

  function consoleEmit(level, args) {
    try { host.log(level, stringifyLogArgs(args)); } catch (e) { }
  }

  globalThis.console = {
    log: function () { consoleEmit('log', arguments); },
    info: function () { consoleEmit('info', arguments); },
    warn: function () { consoleEmit('warn', arguments); },
    error: function () { consoleEmit('error', arguments); },
    debug: function () { consoleEmit('debug', arguments); },
    trace: function () { consoleEmit('trace', arguments); }
  };

  // ---- Buffer ----------------------------------------------------------------------------------
  // A Node Buffer is a Uint8Array; only the encodings LX's utils hand out are implemented, and the actual
  // UTF-8/base64 work is done in Kotlin rather than re-implemented here.

  function toHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      var part = bytes[i].toString(16);
      out += part.length === 1 ? '0' + part : part;
    }
    return out;
  }

  function fromHex(hex) {
    var clean = String(hex === undefined || hex === null ? '' : hex).replace(/[^0-9a-fA-F]/g, '');
    if (clean.length % 2 !== 0) clean = '0' + clean;
    var out = new Uint8Array(clean.length / 2);
    for (var i = 0; i < out.length; i++) {
      out[i] = parseInt(clean.substr(i * 2, 2), 16);
    }
    return out;
  }

  function bytesOf(value) {
    if (value instanceof Uint8Array) return value;
    if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) return new Uint8Array(value);
    if (Object.prototype.toString.call(value) === '[object Array]') {
      var copy = new Uint8Array(value.length);
      for (var i = 0; i < value.length; i++) copy[i] = value[i] & 0xff;
      return copy;
    }
    if (typeof value === 'string') return fromHex(host.utf8ToHex(value));
    throw new TypeError('lx.utils: expected a Buffer');
  }

  function makeBuffer(bytes) {
    var buffer = new Uint8Array(bytes);
    buffer.toString = function (format, start, end) {
      var view = buffer;
      if (start !== undefined || end !== undefined) {
        view = buffer.subarray(start === undefined ? 0 : start, end === undefined ? buffer.length : end);
      }
      var hex = toHex(view);
      if (format === undefined || format === null || format === 'utf8' || format === 'utf-8') {
        return host.hexToUtf8(hex);
      }
      if (format === 'hex') return hex;
      if (format === 'base64') return host.hexToBase64(hex);
      if (format === 'binary' || format === 'latin1') return host.hexToUtf8(hex);
      throw new Error('Buffer.toString: unsupported encoding ' + format);
    };
    return buffer;
  }

  var LXBuffer = {
    from: function (value, encoding) {
      if (value === undefined || value === null) throw new TypeError('Buffer.from: value is required');
      if (typeof value === 'string') {
        if (encoding === 'hex') return makeBuffer(fromHex(value));
        if (encoding === 'base64') return makeBuffer(fromHex(host.base64ToHex(value)));
        return makeBuffer(fromHex(host.utf8ToHex(value)));
      }
      if (typeof value === 'number') {
        if (!isFinite(value) || value < 0) throw new RangeError('Buffer.from: invalid length');
        return makeBuffer(new Uint8Array(Math.floor(value)));
      }
      if (value && value.type === 'Buffer' && value.data) return makeBuffer(bytesOf(value.data));
      return makeBuffer(bytesOf(value));
    },
    alloc: function (size) {
      var n = Number(size);
      if (!isFinite(n) || n < 0) throw new RangeError('Buffer.alloc: invalid length');
      return makeBuffer(new Uint8Array(Math.floor(n)));
    },
    isBuffer: function (value) { return value instanceof Uint8Array; },
    bufToString: function (buffer, format) { return makeBuffer(bytesOf(buffer)).toString(format); }
  };

  // ---- crypto / zlib ---------------------------------------------------------------------------
  // Every host call returns a JSON envelope: a native failure then becomes a normal, catchable JS `Error`
  // (a source is allowed to try/catch a decrypt it cannot perform), never an engine-level exception.

  function callHostEnvelope(fn, args) {
    var raw = fn.apply(host, args);
    var parsed = JSON.parse(raw);
    if (!parsed.ok) throw new Error(parsed.error || 'lx.utils: host failure');
    return parsed.value;
  }

  var LXCrypto = {
    md5: function (value) {
      return host.md5(String(value === undefined || value === null ? '' : value));
    },
    randomBytes: function (size) {
      var n = Number(size);
      if (!isFinite(n) || n < 0) throw new RangeError('lx.utils.crypto.randomBytes: invalid size');
      // LX returns a Buffer (desktop: node crypto.randomBytes; sources call `.map`, `.reverse`, `.toString`).
      return makeBuffer(fromHex(host.randomBytes(Math.floor(n))));
    },
    aesEncrypt: function (buffer, mode, key, iv) {
      var value = callHostEnvelope(host.aesEncrypt, [
        String(mode === undefined || mode === null ? '' : mode),
        toHex(bytesOf(key)),
        toHex(bytesOf(iv === undefined || iv === null ? new Uint8Array(0) : iv)),
        toHex(bytesOf(buffer))
      ]);
      return makeBuffer(fromHex(value));
    },
    rsaEncrypt: function (buffer, key) {
      var value = callHostEnvelope(host.rsaEncrypt, [
        toHex(bytesOf(buffer)),
        String(key === undefined || key === null ? '' : key)
      ]);
      return makeBuffer(fromHex(value));
    }
  };

  var LXZlib = {
    inflate: function (buffer) {
      return new Promise(function (resolve, reject) {
        try {
          resolve(makeBuffer(fromHex(callHostEnvelope(host.zlibInflate, [toHex(bytesOf(buffer))]))));
        } catch (e) { reject(e); }
      });
    },
    deflate: function (buffer) {
      return new Promise(function (resolve, reject) {
        try {
          resolve(makeBuffer(fromHex(callHostEnvelope(host.zlibDeflate, [toHex(bytesOf(buffer))]))));
        } catch (e) { reject(e); }
      });
    }
  };

  // ---- timers ----------------------------------------------------------------------------------
  // LX mobile provides setTimeout/clearTimeout; setInterval/clearInterval/queueMicrotask are a documented
  // superset of this host. Each timer keeps a host `timerSleep` job pending, which is what advances time.

  var timerSeq = 0;
  var timerGen = 0;
  var timers = Object.create(null);

  function timerNow() { return Date.now(); }

  function clearTimerById(id) {
    var timer = timers[id];
    if (timer) {
      timer.cancelled = true;
      delete timers[id];
    }
  }

  function scheduleTimer(id) {
    var timer = timers[id];
    if (!timer || timer.cancelled) return;
    var delayMs = Math.max(0, timer.due - timerNow());
    var myGen = timerGen;
    host.timerSleep(delayMs).then(function () {
      var current = timers[id];
      if (!current || current.cancelled || myGen !== timerGen) return;
      if (current.intervalMs > 0) current.due = timerNow() + current.intervalMs;
      else delete timers[id];
      try {
        current.fn.apply(undefined, current.args);
      } catch (error) {
        var message = error && error.message ? String(error.message) : String(error);
        try { host.log('error', 'timer callback threw: ' + message); } catch (ignored) { }
      }
      if (current.intervalMs > 0 && timers[id] === current && !current.cancelled) scheduleTimer(id);
    }, function () { });
  }

  function addTimer(fn, ms, args, intervalMs) {
    if (typeof fn !== 'function') throw new TypeError('timer: callback must be a function');
    var delay = Number(ms);
    if (!isFinite(delay) || delay < 0) delay = 0;
    var id = ++timerSeq;
    timers[id] = {
      due: timerNow() + delay,
      intervalMs: intervalMs,
      fn: fn,
      args: args,
      cancelled: false
    };
    scheduleTimer(id);
    return id;
  }

  globalThis.setTimeout = function (fn, ms) {
    return addTimer(fn, ms, Array.prototype.slice.call(arguments, 2), 0);
  };
  globalThis.setInterval = function (fn, ms) {
    return addTimer(fn, ms, Array.prototype.slice.call(arguments, 2), Math.max(1, Number(ms) || 1));
  };
  globalThis.clearTimeout = function (id) { clearTimerById(Number(id)); };
  globalThis.clearInterval = function (id) { clearTimerById(Number(id)); };
  globalThis.queueMicrotask = function (fn) {
    if (typeof fn !== 'function') throw new TypeError('queueMicrotask: callback must be a function');
    Promise.resolve().then(fn);
  };
  // Called by the host when a run ends: invalidates every pending sleeper without touching JS jobs the host
  // cannot cancel. A woken sleeper sees the generation change and does nothing.
  globalThis.__lxClearTimers = function () {
    timerGen++;
    timers = Object.create(null);
  };

  // ---- lx.request ------------------------------------------------------------------------------
  // The documented shape is callback-based with a cancel handle. The host returns the response as raw bytes;
  // the JSON parse happens here so `resp.body` matches LX (`JSON.parse` when possible, else the text).

  var nextRequestId = 1;
  var cancelledRequests = Object.create(null);

  function lxRequest(url, options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    if (typeof callback !== 'function') {
      throw new TypeError('lx.request(url, options, callback): callback must be a function');
    }
    var id = nextRequestId++;
    var promise = host.request(
      String(url),
      JSON.stringify(options === undefined || options === null ? {} : options),
      id
    );
    promise.then(function (payload) {
      if (cancelledRequests[id]) { delete cancelledRequests[id]; return; }
      var parsed;
      try { parsed = JSON.parse(payload); } catch (e) {
        callback(new Error('lx.request: the host returned an unreadable response'), null, null);
        return;
      }
      if (!parsed.ok) {
        callback(new Error(parsed.error || 'lx.request failed'), null, null);
        return;
      }
      var data = parsed.response || {};
      var raw = makeBuffer(fromHex(data.bodyHex || ''));
      var text = raw.toString('utf8');
      var body = text;
      try { body = JSON.parse(text); } catch (e) { body = text; }
      var response = {
        statusCode: data.statusCode,
        statusMessage: data.statusMessage || '',
        headers: data.headers || {},
        bytes: raw.length,
        raw: raw,
        body: body
      };
      callback(null, response, body);
    }, function (error) {
      if (cancelledRequests[id]) { delete cancelledRequests[id]; return; }
      callback(new Error(error && error.message ? error.message : String(error)), null, null);
    });
    return function cancel() {
      cancelledRequests[id] = true;
      try { host.cancel(id); } catch (e) { }
    };
  }

  // ---- lx --------------------------------------------------------------------------------------

  var handlers = Object.create(null);
  var initedSent = false;
  var updateAlertSent = false;

  var EVENT_NAMES = { inited: 'inited', request: 'request', updateAlert: 'updateAlert' };

  function lxOn(name, handler) {
    if (name !== EVENT_NAMES.request) {
      return Promise.reject(new Error('The event is not supported: ' + name));
    }
    if (typeof handler !== 'function') {
      return Promise.reject(new TypeError('lx.on(name, handler): handler must be a function'));
    }
    handlers[name] = handler;
    return Promise.resolve();
  }

  function lxSend(name, data) {
    switch (name) {
      case EVENT_NAMES.inited: {
        if (initedSent) return Promise.reject(new Error('Script is inited'));
        initedSent = true;
        var error = host.inited(JSON.stringify(data === undefined ? null : data));
        return error ? Promise.reject(new Error(error)) : Promise.resolve();
      }
      case EVENT_NAMES.updateAlert: {
        if (updateAlertSent) return Promise.reject(new Error('The update alert can only be called once.'));
        updateAlertSent = true;
        // The desktop host shows the author's update dialog; this app has no such surface, so the event is
        // accepted and ignored rather than rejected — a source must not fail because of it.
        return Promise.resolve();
      }
      default:
        return Promise.reject(new Error('The event is not supported: ' + name));
    }
  }

  var scriptInfo = JSON.parse(host.scriptInfo());

  globalThis.lx = {
    version: scriptInfo.apiVersion,
    env: 'mobile',
    currentScriptInfo: scriptInfo.scriptInfo,
    EVENT_NAMES: EVENT_NAMES,
    on: lxOn,
    send: lxSend,
    request: lxRequest,
    utils: { buffer: LXBuffer, crypto: LXCrypto, zlib: LXZlib }
  };

  // Called by the host to invoke the registered handler. A handler must return a Promise; `then` accepts a
  // plain value too, so a non-conforming source produces a result instead of wedging the request. The result
  // is a JSON envelope because only strings cross the boundary.
  globalThis.__lxDispatchPending = function () {
    var name = globalThis.__lxPendingEvent;
    var payload = globalThis.__lxPendingPayload;
    var handler = handlers[name];
    if (typeof handler !== 'function') {
      return Promise.resolve(JSON.stringify({ ok: false, error: 'script has no handler for event: ' + name }));
    }
    return Promise.resolve()
      .then(function () { return handler(JSON.parse(payload)); })
      .then(function (value) {
        return JSON.stringify({ ok: true, value: value === undefined ? null : value });
      })
      .catch(function (error) {
        var message = error && error.message ? String(error.message) : String(error);
        var stack = error && error.stack ? String(error.stack) : null;
        return JSON.stringify({ ok: false, error: message, stack: stack });
      });
  };

  globalThis.__lxBootstrapOk = true;
})();
"""
