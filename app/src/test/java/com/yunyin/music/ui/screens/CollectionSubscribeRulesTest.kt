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
    fun `the two labels differ and are never blank`() {
        val off = CollectionSubscribeRules.label(false)
        val on = CollectionSubscribeRules.label(true)
        assertNotEquals(off, on)
        assertTrue(off.isNotBlank())
        assertTrue(on.isNotBlank())
    }
}
