package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxImportResult
import com.yunyin.music.data.lxsource.LxScriptFormat
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourceManager
import com.yunyin.music.data.lxsource.LxSourceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The "this is a Listen1/Node plugin bundle" refusal.
 *
 * The user picked the same kind of file twice (`tx..js`), so the generic "missing header comment" refusal was
 * clearly not communicating the problem. These tests pin the two halves of the fix: the classifier recognises the
 * class without false-positiving on real LX sources or on ordinary malformed JavaScript, and the import path turns
 * that into the specific message plus a log detail the user can copy.
 */
class LxScriptFormatTest {

    /** Shaped like the reported file: a webpack bundle with many CommonJS exports and an embedded LX host. */
    private val nodeBundle = """
        /******/ (() => { // webpackBootstrap
        /******/ 	var __webpack_modules__ = ({
        /***/ 123: /*!******************!*\
          !*** ./src/plugin.js ***!
          \******************/
          module.exports = {
            EVENT_NAMES: { request: 'request', inited: 'inited' },
            pluginInfo: { id: 'listen1', name: 'x' },
            musicSearch: function () {},
          }
        /***/ }),
        /***/ 456:
          const path = require('path')
          module.exports = path.dirname(__dirname)
        /***/ }),
        /***/ 789:
          if (typeof globalThis.lx === 'undefined') {
            globalThis.lx = { on: function () {}, send: function () {}, EVENT_NAMES: {} }
          }
          module.exports = {}
        /***/ })
        /******/ })()
    """.trimIndent()

    @Test
    fun `a webpack node bundle is recognised and described`() {
        val detail = LxScriptFormat.detectNodeBundle(nodeBundle)
        assertNotNull(detail)
        assertTrue(detail!!.hasWebpackBootstrap)
        assertTrue(detail.moduleExportsCount >= 3)
        assertTrue(detail.hasDirname)
        // The bundle ships an LX host emulator; it is recorded for the log but does not make the file importable.
        assertTrue(detail.hasLxHostMarker)
        assertTrue(detail.describe().contains("webpackBootstrap=true"))
        assertTrue(detail.describe().contains("__dirname=true"))
    }

    @Test
    fun `the user-facing message names the format and the practical consequence`() {
        assertTrue(LxScriptFormat.NODE_BUNDLE_MESSAGE.contains("Listen1"))
        assertTrue(LxScriptFormat.NODE_BUNDLE_MESSAGE.contains("Node"))
        assertTrue(LxScriptFormat.NODE_BUNDLE_MESSAGE.contains("不是洛雪音源"))
        assertTrue(LxScriptFormat.NODE_BUNDLE_MESSAGE.contains("QQ 音乐"))
        assertTrue(LxScriptFormat.NODE_BUNDLE_MESSAGE.contains("不需要"))
    }

    @Test
    fun `a real LX source is never classified as a node bundle`() {
        val source = """
            /*!
             * @name 测试音源
             * @version 1.0.0
             * @description x
             * @author y
             */
            const { EVENT_NAMES, on, send } = globalThis.lx
            module.exports = {}
        """.trimIndent()
        assertNull(LxScriptFormat.detectNodeBundle(source))
    }

    @Test
    fun `ordinary malformed javascript keeps the generic refusal`() {
        assertNull(LxScriptFormat.detectNodeBundle("function broken( {"))
        assertNull(LxScriptFormat.detectNodeBundle(""))
    }

    @Test
    fun `one module exports mention alone is not enough`() {
        assertNull(LxScriptFormat.detectNodeBundle("module.exports = { a: 1 }"))
    }

    @Test
    fun `three module exports mentions identify a CommonJS bundle`() {
        val text = "module.exports = 1\nmodule.exports = 2\nmodule.exports = 3"
        assertNotNull(LxScriptFormat.detectNodeBundle(text))
    }

    @Test
    fun `importing the bundle produces the specific message and logs the detail`() = runBlocking {
        val dir = Files.createTempDirectory("lx-format").toFile()
        val log = LxSourceLog(File(dir, "last-error.txt"))
        val manager = LxSourceManager(null, LxSourceStore(dir, log), log)

        val result = manager.importScript(nodeBundle)
        assertTrue(result is LxImportResult.Failure)
        assertEquals(LxScriptFormat.NODE_BUNDLE_MESSAGE, (result as LxImportResult.Failure).message)

        val logged = log.readLast()
        assertNotNull(logged)
        assertTrue(logged!!.contains("Listen1/Node"))
        assertTrue(logged.contains("webpackBootstrap=true"))
    }
}
