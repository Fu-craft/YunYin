package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the 收藏/取消收藏 menu wording for both subscribed states. */
class CollectionSubscribeRulesTest {

    @Test
    fun `an unsubscribed collection offers 收藏`() {
        assertEquals("收藏", CollectionSubscribeRules.label(subscribed = false))
    }

    @Test
    fun `a subscribed collection offers the undo action 取消收藏`() {
        assertEquals("取消收藏", CollectionSubscribeRules.label(subscribed = true))
    }

    @Test
    fun `subscribing then unsubscribing returns to the original state`() {
        assertTrue(CollectionSubscribeRules.next(false))
        assertTrue(!CollectionSubscribeRules.next(true))
    }

    @Test
    fun `the label flips on every toggle`() {
        val off = false
        val on = CollectionSubscribeRules.next(off)
        assertEquals("收藏", CollectionSubscribeRules.label(off))
        assertEquals("取消收藏", CollectionSubscribeRules.label(on))
        assertNotEquals(
            CollectionSubscribeRules.label(on),
            CollectionSubscribeRules.label(CollectionSubscribeRules.next(on)),
        )
    }

    @Test
    fun `the two labels differ and are never blank`() {
        val off = CollectionSubscribeRules.label(false)
        val on = CollectionSubscribeRules.label(true)
        assertNotEquals(off, on)
        assertTrue(off.isNotBlank())
        assertTrue(on.isNotBlank())
    }
}
