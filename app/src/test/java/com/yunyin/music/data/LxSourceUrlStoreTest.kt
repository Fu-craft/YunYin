package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxScriptHeader
import com.yunyin.music.data.lxsource.LxSourceDescriptor
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Origin persistence and atomic re-fetch at the store level.
 *
 * 重新拉取 replaces a source whose content id changes, so it has to do three things in a safe order: keep the
 * enabled state, carry the origin over, and only delete the old script file after the metadata is consistent. A
 * failure in the middle that left both files or neither would show up as a missing source after a restart.
 */
class LxSourceUrlStoreTest {

    private val header = LxScriptHeader(name = "网络音源", version = "1.0.0")
    private val wyDescriptor = listOf(
        LxSourceDescriptor(LxSourceKey.WY, "网易云", listOf("musicUrl"), listOf("128k")),
    )

    private fun store(): Pair<LxSourceStore, File> {
        val dir = Files.createTempDirectory("url-store").toFile()
        val log = LxSourceLog(File(dir, "last-error.txt"))
        return LxSourceStore(dir, log) to dir
    }

    @Test
    fun `the origin URL is persisted and read back`() {
        val (store, _) = store()
        val origin = "https://example.com/source.js"
        store.addOrReplace(header, wyDescriptor, "/*! @name 网络音源 */\nv1", originUrl = origin)
        val loaded = store.load().single()
        assertEquals(origin, loaded.originUrl)
        assertEquals("网络音源", loaded.header.name)
    }

    @Test
    fun `a local import has no origin`() {
        val (store, _) = store()
        store.addOrReplace(header, wyDescriptor, "/*! @name 网络音源 */\nv1")
        assertEquals(null, store.load().single().originUrl)
    }

    @Test
    fun `re-fetch replaces the entry, keeps enabled state and origin, and removes the old script`() {
        val (store, dir) = store()
        val origin = "https://example.com/source.js"
        val old = store.addOrReplace(header, wyDescriptor, "v1 content", originUrl = origin).source
        assertTrue(old.enabled)

        val refreshed = store.addOrReplace(
            header.copy(version = "2.0.0"),
            wyDescriptor,
            "v2 content",
            originUrl = origin,
            keepEnabledFrom = old,
        ).source

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("v2 content", store.readScript(loaded.single().id))
        assertEquals(origin, loaded.single().originUrl)
        assertTrue("enabled state must survive an update", loaded.single().enabled)
        assertNotNull(loaded.single().id)
        // The superseded script file is gone; only the new one remains.
        assertEquals(false, File(dir, "${old.id}.js").exists())
        assertEquals(true, File(dir, "${refreshed.id}.js").exists())
    }

    @Test
    fun `re-fetch keeps a disabled source disabled`() {
        val (store, _) = store()
        val origin = "https://example.com/source.js"
        val old = store.addOrReplace(header, wyDescriptor, "v1", originUrl = origin).source
        store.setEnabled(old.id, false)
        val disabled = store.load().single()
        assertFalse(disabled.enabled)

        store.addOrReplace(header, wyDescriptor, "v2", originUrl = origin, keepEnabledFrom = disabled)
        assertEquals(1, store.load().size)
        assertFalse(store.load().single().enabled)
    }

    @Test
    fun `re-fetch reports a collision instead of stealing another source's key`() {
        val (store, _) = store()
        val existing = store.addOrReplace(header.copy(name = "A"), wyDescriptor, "a-content").source
        assertTrue(existing.enabled)
        val other = store.addOrReplace(
            header.copy(name = "B"),
            listOf(LxSourceDescriptor(LxSourceKey.TX, "QQ", listOf("musicUrl"), listOf("128k"))),
            "b-content",
        ).source
        assertTrue(other.enabled)

        // The refreshed B now claims wy too, colliding with A: it is stored disabled and the collision is named.
        val result = store.addOrReplace(
            header.copy(name = "B2"),
            wyDescriptor,
            "b2-content",
            originUrl = "https://example.com/b.js",
            keepEnabledFrom = other,
        )
        assertEquals(LxSourceKey.WY, result.collision)
        assertEquals("A", result.collidedWith)
        assertFalse(result.source.enabled)
    }
}
