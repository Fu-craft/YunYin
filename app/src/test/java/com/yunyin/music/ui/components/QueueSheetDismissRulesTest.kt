package com.yunyin.music.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue sheet's drag-to-dismiss threshold, pinned so it cannot drift from the player's own rule
 * (plan 001): > 120 dp of travel **or** a release faster than 0.11 px/ms. The values are expressed in
 * px because that is what the composable has at drag time; the density conversion happens before the
 * call.
 */
class QueueSheetDismissRulesTest {

    private val distancePx = 120f * 3f // 120 dp at density 3

    @Test
    fun `a pull past the distance dismisses`() {
        assertTrue(QueueSheetDismissRules.shouldDismiss(distancePx + 1f, distancePx, 0f))
    }

    @Test
    fun `a fast flick dismisses below the distance`() {
        assertTrue(QueueSheetDismissRules.shouldDismiss(offsetPx = 40f, distancePx, velocityPxPerMs = 0.12f))
    }

    @Test
    fun `a short slow release springs back`() {
        assertFalse(QueueSheetDismissRules.shouldDismiss(offsetPx = 40f, distancePx, velocityPxPerMs = 0.05f))
    }

    @Test
    fun `moving up never dismisses`() {
        assertFalse(QueueSheetDismissRules.shouldDismiss(offsetPx = -80f, distancePx, velocityPxPerMs = 0f))
    }

    @Test
    fun `the thresholds match the player's documented rule`() {
        assertTrue(QueueSheetDismissRules.DistanceDp == 120f)
        assertTrue(QueueSheetDismissRules.VelocityPxPerMs == 0.11f)
    }
}
