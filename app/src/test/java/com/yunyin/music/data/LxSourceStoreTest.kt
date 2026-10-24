// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxScriptHeader
import com.yunyin.music.data.lxsource.LxSourceDescriptor
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourceStore
import com.yunyin.music.data.lxsource.LxToggleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The persistence and collision rule, exercised on a temp directory.
 *
 * These are the parts where a bug loses data rather than showing a wrong screen: a metadata write that is not
 * atomic, an id that changes on re-import, or a collision rule that is enforced in the UI but not in the store.
 * The store takes a directory rather than a `Context` for exactly this reason.
 */
class LxSourceStoreTest {

    private fun newStore(): Pair<LxSourceStore, java.io.File> {
        val dir = Files.createTempDirectory("lx-store-test").toFile()
        return LxSourceStore(dir, LxSourceLog(dir)) to dir
    }

    private fun header(name: String = "测试源") = LxScriptHeader(
        name = name,
        description = "描述",
        version = "1.0.0",
        author = "作者",
        homepage = "https://example.com",
    )

    private fun descriptor(vararg keys: LxSourceKey) = listOf(
        LxSourceDescriptor(
            key = keys.first(),
            name = null,
            actions = listOf("musicUrl"),
            qualities = listOf("128k"),
        ),
    ) + keys.drop(1).map { LxSourceDescriptor(it, null, listOf("musicUrl"), listOf("128k")) }

    @Test
    fun `an imported source round trips through the sidecar`() {
        val (store, _) = newStore()
        val result = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")

        val loaded = store.load()
        assertEquals(1, loaded.size)
        val source = loaded.single()
        assertEquals(result.source.id, source.id)
        assertEquals("测试源", source.header.name)
        assertEquals("描述", source.header.description)
        assertEquals("1.0.0", source.header.version)
        assertEquals("作者", source.header.author)
        assertEquals("https://example.com", source.header.homepage)
        assertEquals(listOf(LxSourceKey.WY), source.keys)
        // A first, non-colliding import is enabled: it was imported to be used.
        assertTrue(source.enabled)
        assertEquals("var a = 1", store.readScript(source.id))
    }

    @Test
    fun `re-importing identical content updates one entry and keeps its state`() {
        val (store, _) = newStore()
        val first = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")
        assertTrue(store.setEnabled(first.source.id, false) is LxToggleResult.Ok)

        val again = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")
        assertEquals(first.source.id, again.source.id)
        assertEquals(1, store.load().size)
        // The user already decided this source is off; a re-import must not silently switch it back on.
        assertFalse(store.load().single().enabled)
    }

    @Test
    fun `a changed script is a different entry`() {
        val (store, _) = newStore()
        val a = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")
        val b = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 2")
        assertTrue(a.source.id != b.source.id)
        assertEquals(2, store.load().size)
        // The new version collides with the enabled old one, so it is stored but not enabled.
        assertNotNull(b.collision)
        assertEquals(LxSourceKey.WY, b.collision)
        assertFalse(store.load().first { it.id == b.source.id }.enabled)
    }

    @Test
    fun `a source whose keys are free starts enabled`() {
        val (store, _) = newStore()
        store.addOrReplace(header("A"), descriptor(LxSourceKey.WY), "var a = 1")
        val second = store.addOrReplace(header("B"), descriptor(LxSourceKey.TX), "var b = 1")
        assertNull(second.collision)
        assertEquals(2, store.load().count { it.enabled })
    }

    @Test
    fun `enabling a colliding source is refused and names the holder`() {
        val (store, _) = newStore()
        val a = store.addOrReplace(header("A"), descriptor(LxSourceKey.WY), "var a = 1")
        val b = store.addOrReplace(header("B"), descriptor(LxSourceKey.WY), "var b = 1")
        assertFalse(store.load().first { it.id == b.source.id }.enabled)

        val result = store.setEnabled(b.source.id, true)
        assertTrue(result is LxToggleResult.Collision)
        result as LxToggleResult.Collision
        assertEquals(LxSourceKey.WY, result.key)
        assertEquals("A", result.otherName)
        // Refused means refused: the other source is still the only enabled one.
        assertTrue(store.load().first { it.id == a.source.id }.enabled)
        assertFalse(store.load().first { it.id == b.source.id }.enabled)
    }

    @Test
    fun `after the holder is removed the other source can be enabled`() {
        val (store, _) = newStore()
        val a = store.addOrReplace(header("A"), descriptor(LxSourceKey.WY), "var a = 1")
        val b = store.addOrReplace(header("B"), descriptor(LxSourceKey.WY), "var b = 1")
        assertTrue(store.delete(a.source.id))
        assertTrue(store.setEnabled(b.source.id, true) is LxToggleResult.Ok)
        assertTrue(store.load().single().enabled)
    }

    @Test
    fun `disabling is always allowed`() {
        val (store, _) = newStore()
        val a = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")
        assertTrue(store.setEnabled(a.source.id, false) is LxToggleResult.Ok)
        assertFalse(store.load().single().enabled)
        // Disabling an already-disabled source is a no-op, not an error.
        assertTrue(store.setEnabled(a.source.id, false) is LxToggleResult.Ok)
    }

    @Test
    fun `deleting removes the metadata entry and the script file`() {
        val (store, dir) = newStore()
        val a = store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")
        val file = store.scriptFile(a.source.id)
        assertTrue(file.isFile)
        assertTrue(store.delete(a.source.id))
        assertFalse(file.exists())
        assertTrue(store.load().isEmpty())
        // Deleting an id that is not there is reported, not silently ignored.
        assertFalse(store.delete(a.source.id))
        assertTrue(java.io.File(dir, "sources.json").isFile)
    }

    @Test
    fun `a corrupt sidecar reads as nothing imported instead of crashing`() {
        val (store, dir) = newStore()
        store.addOrReplace(header(), descriptor(LxSourceKey.WY), "var a = 1")
        java.io.File(dir, "sources.json").writeText("{ not json")
        assertTrue(store.load().isEmpty())
        // And the next write replaces it, so the store heals.
        store.addOrReplace(header("B"), descriptor(LxSourceKey.TX), "var b = 1")
        assertEquals(1, store.load().size)
    }

    @Test
    fun `the content id is stable for the same script and different for another`() {
        val (store, _) = newStore()
        assertEquals(store.idFor("var a = 1"), store.idFor("var a = 1"))
        assertTrue(store.idFor("var a = 1") != store.idFor("var a = 2"))
        assertEquals(16, store.idFor("var a = 1").length)
    }
}
