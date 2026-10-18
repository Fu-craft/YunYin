package com.yunyin.music.data.lxsource

/**
 * Recognises the file class that keeps getting picked by mistake: a **Listen1 / Node plugin bundle**.
 *
 * ## Why this exists
 *
 * The LX custom-source protocol is a tiny JavaScript API (`globalThis.lx`, `lx.on`, `lx.send`) inside a source
 * header comment. A different ecosystem — Listen1-style plugins bundled for Node with webpack — looks superficially
 * similar ("it's a music source script") but cannot run here at all: it needs `require`, `module.exports`,
 * `__dirname`, `process`, `Buffer`, and the Listen1 plugin contract, i.e. a Node runtime this app deliberately does
 * not embed. The generic refusal ("缺少自定义源信息注释") was technically right and practically useless: the user was
 * left thinking their file was broken or that the app was missing a feature.
 *
 * So a file with the Node-bundle signature and **no LX header** gets a specific message that names the format and
 * says the practical thing: the app's built-in QQ Music provider already searches and plays without any import.
 *
 * Pure (string in, verdict out) so the classifier is unit-testable: the import UI path itself needs an engine.
 */
internal object LxScriptFormat {

    /** What the user should read when they picked a Node bundle. */
    const val NODE_BUNDLE_MESSAGE =
        "这不是洛雪音源（是 Listen1/Node 插件包）：它需要 Node.js 运行环境，本应用不支持导入。" +
            "QQ 音乐的搜索与播放由应用内置音源完成，不需要这个文件。"

    data class NodeBundleDetail(
        val hasWebpackBootstrap: Boolean,
        val moduleExportsCount: Int,
        val hasDirname: Boolean,
        val hasLxHostMarker: Boolean,
    ) {
        fun describe(): String = buildString {
            append("webpackBootstrap=").append(hasWebpackBootstrap)
            append(" module.exports×").append(moduleExportsCount)
            append(" __dirname=").append(hasDirname)
            append(" globalThis.lx=").append(hasLxHostMarker)
        }
    }

    /**
     * The Node-bundle signature, or null when [text] does not look like one.
     *
     * The decision needs two signals, because either one alone has false positives: `module.exports` appears in
     * CommonJS-style code that is otherwise a valid source, and `__dirname` is occasionally mentioned in comments.
     * A file with **no `@name` header** (the caller only reaches this after the header parse failed) plus a
     * webpack bootstrap, several `module.exports`, or a `__dirname` is unambiguously this class.
     */
    fun detectNodeBundle(text: String): NodeBundleDetail? {
        // A real LX source is identified by its header; this classifier must never override a header failure that
        // belongs to a source-shaped file.
        if (text.contains("@name")) return null
        val hasLxHostMarker = text.contains("globalThis.lx") || text.contains("lx.send(")
        val detail = NodeBundleDetail(
            hasWebpackBootstrap = text.contains("webpackBootstrap"),
            moduleExportsCount = countOf(text, "module.exports"),
            hasDirname = text.contains("__dirname"),
            hasLxHostMarker = hasLxHostMarker,
        )
        val looksBundled = detail.hasWebpackBootstrap || detail.moduleExportsCount >= 3 || detail.hasDirname
        if (!looksBundled) return null
        // An embedded LX host emulator is exactly what these bundles ship (`EVENT_NAMES` + a fake `lx`), so the
        // marker is recorded for the log but does not make the file importable.
        return detail
    }

    private fun countOf(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}
