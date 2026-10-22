package com.yunyin.music.data

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flyme lyric notification: id/channel stability and the apply/clear flag arithmetic. */
class FlymeLyricNotificationRulesTest {

    @Test
    fun `the lyric id is stable and distinct from the media notification`() {
        assertEquals(2001, FlymeLyricNotificationRules.NOTIFICATION_ID)
        assertNotEquals(1001, FlymeLyricNotificationRules.NOTIFICATION_ID)
        assertEquals("flyme_status_bar_lyrics", FlymeLyricNotificationRules.CHANNEL_ID)
    }

    @Test
    fun `an active ticker is resident and carries both Flyme flags`() {
        val flags = FlymeLyricNotificationRules.activeFlags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        assertTrue(flags and Notification.FLAG_NO_CLEAR != 0)
        assertTrue(flags and FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER != 0)
        assertTrue(flags and FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER != 0)
    }

    @Test
    fun `clearing keeps the notification resident but removes both Flyme flags`() {
        val flags = FlymeLyricNotificationRules.clearedFlags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        assertTrue(flags and Notification.FLAG_NO_CLEAR != 0)
        assertFalse(flags and FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER != 0)
        assertFalse(flags and FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER != 0)
    }

    @Test
    fun `the self-test matrix has four distinct variants and keeps the stable id`() {
        val variants = FlymeLyricNotificationRules.TestVariant.entries
        assertEquals(4, variants.size)
        assertEquals(4, variants.map { it.label }.toSet().size)
        assertEquals("A-doc-literal", variants[0].label)
        assertTrue(variants[0].silent == false && variants[0].onlyAlertOnce == false)
        assertTrue(variants[1].silent == true && variants[1].onlyAlertOnce == true)
        assertTrue(variants[2].label.startsWith("C"))
        assertTrue(variants[3].minImportance)
        assertEquals(2001, FlymeLyricNotificationRules.NOTIFICATION_ID)
    }

    @Test
    fun `meizu is recognised from manufacturer or brand`() {
        assertTrue(FlymeLyricNotificationRules.isFlymeManufacturer("Meizu", null))
        assertTrue(FlymeLyricNotificationRules.isFlymeManufacturer(null, "meizu"))
        assertFalse(FlymeLyricNotificationRules.isFlymeManufacturer("Xiaomi", "Redmi"))
        assertFalse(FlymeLyricNotificationRules.isFlymeManufacturer(null, null))
    }
}
