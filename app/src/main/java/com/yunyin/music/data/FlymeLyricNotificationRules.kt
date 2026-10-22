package com.yunyin.music.data

import android.app.Notification

/**
 * Constants and flag arithmetic for the Flyme status-bar lyric notification (official 适配说明).
 *
 * The doc's sample: a **standalone** notification with its own channel, `PRIORITY_MAX`, a 60 dp-class small icon,
 * `FLAG_NO_CLEAR`, and both private flags only while a lyric is active. Pure so the flag transitions (apply/clear),
 * the stable id and the channel name are unit-tested.
 */
object FlymeLyricNotificationRules {

    /** Same id on every update; deliberately different from Media3's media notification (1001). */
    const val NOTIFICATION_ID = 2001

    /** Own channel, as the doc's `createNotificationChannel()` requires. */
    const val CHANNEL_ID = "flyme_status_bar_lyrics"

    /** Flyme's private flags (fallbacks when reflection is blocked on a Meizu device). */
    const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
    const val FLAG_ONLY_UPDATE_TICKER = 0x02000000

    /** Flags for an active ticker post: resident + both Flyme flags. */
    fun activeFlags(showTicker: Int, updateTicker: Int): Int =
        Notification.FLAG_NO_CLEAR or showTicker or updateTicker

    /** Flags for the clearing post: resident, but both Flyme flags removed (stops the scroll). */
    fun clearedFlags(showTicker: Int, updateTicker: Int): Int =
        Notification.FLAG_NO_CLEAR and (showTicker or updateTicker).inv()

    /** The in-app self-test matrix: four ways the ticker may need to be posted, one per 2.5 s. */
    enum class TestVariant(val label: String, val silent: Boolean, val onlyAlertOnce: Boolean, val minImportance: Boolean) {
        A("A-doc-literal", silent = false, onlyAlertOnce = false, minImportance = false),
        B("B-silent", silent = true, onlyAlertOnce = true, minImportance = false),
        C("C-launcher-icon", silent = false, onlyAlertOnce = false, minImportance = false),
        D("D-min-importance", silent = false, onlyAlertOnce = false, minImportance = true),
    }

    val isFlymeManufacturer: (String?, String?) -> Boolean = { manufacturer, brand ->
        listOf(manufacturer, brand).any { value -> value?.trim()?.equals("meizu", ignoreCase = true) == true }
    }
}
