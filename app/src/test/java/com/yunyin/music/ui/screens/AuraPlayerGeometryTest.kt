package com.yunyin.music.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class PlayerLayoutGeometryTest {
    @Test fun transitionBlurStaysBelowUnchangedMiniMaterialForAllNavigationStyles() {
        for ((style, radius) in listOf("liquid_glass" to 3f, "harmony_os_floating" to 5.22f,
                "hyper_os_floating" to 4.32f)) {
            for (percent in listOf(0, 25, 100, 160, 244, 300)) {
                val mini = playerMiniBlurRadius(style, percent)
                assertEquals(radius * percent / 100f, mini.value, .0001f)
                for (expansion in listOf(-.1f, 0f, .1f, .34f, .8f, 1f, 1.1f)) {
                    val transition = playerTransitionBlurRadius(mini, expansion)
                    assertTrue(transition >= 0.dp)
                    if (percent == 0) assertEquals(0.dp, transition)
                    else assertTrue(transition < mini)
                }
                assertEquals(0.dp, playerTransitionBlurRadius(mini, 0f))
            }
        }
    }

    @Test fun fullscreenCardStaysFourByThreeCenteredAndInsideSystemInsets() {
        for ((width, height) in listOf(320 to 640, 360 to 800, 412 to 915, 800 to 360, 1024 to 1366)) {
            val card = fullscreenPlayerCard(width.dp, height.dp, 24.dp, 24.dp)
            assertEquals(4f / 3f, card.width / card.height, .0001f)
            assertEquals(width / 2f, (card.left + card.width / 2).value, .0001f)
            assertTrue(card.left >= 14.dp)
            assertTrue(card.top >= 24.dp)
            assertTrue(card.width <= 520.dp)
            assertEquals((height - 24 - 14).toFloat(), (card.top + card.height).value, .001f)
        }
    }
    @Test fun fullWidthCoverNeverCreatesNegativePaddingAtFractionalDensities() {
        var reproducedRoundingError = false
        for (pixels in 720..1600 step 7) for (density in listOf(2.625f, 2.75f, 3f, 3.5f)) {
            val width = (pixels / density).dp
            val margin = width * .058f * 1.15f
            val cover = width - margin * 2
            if (((width - cover) / 2 - margin).value < 0f) reproducedRoundingError = true
            val inset = playerCoverInset(width, cover, margin)
            assertTrue(inset.value >= 0f)
            assertEquals(margin.value, (margin + inset).value, .0001f)
        }
        assertTrue("Regression inputs must reproduce the original negative padding", reproducedRoundingError)
    }

    @Test fun smallerCoverAndProgressRemainCenteredAndAligned() {
        val inset = playerCoverInset(400.dp, 280.dp, 26.dp)
        assertEquals(34f, inset.value, .0001f)
        assertEquals(60f, (26.dp + inset).value, .0001f)
    }

    @Test fun landscapeLayoutAndCompactFreeformBreakpointsMatchExpectedViewports() {
        assertFalse(isLandscapePlayerLayout(412.dp, 915.dp))
        assertFalse(isLandscapePlayerLayout(280.dp, 520.dp))
        assertTrue(isLandscapePlayerLayout(850.dp, 390.dp))
        assertTrue(isLandscapePlayerLayout(1024.dp, 700.dp))

        assertTrue(isCompactPlayerWindow(width = 280.dp, height = 520.dp, isMultiWindow = false))
        assertTrue(isCompactPlayerWindow(width = 412.dp, height = 915.dp, isMultiWindow = true))
        assertFalse(isCompactPlayerWindow(width = 412.dp, height = 915.dp, isMultiWindow = false))
    }

    @Test fun landscapePlayerGeometrySeparatesCoverAndRightPaneWithinSafeInsets() {
        val geom = computeLandscapePlayerGeometry(
            width = 844.dp,
            height = 390.dp,
            leftInset = 32.dp,
            topInset = 12.dp,
            rightInset = 16.dp,
            bottomInset = 20.dp,
            showVolume = true,
        )
        assertTrue(geom.leftPaneX >= 32.dp)
        assertTrue(geom.leftPaneX + geom.leftPaneWidth < geom.rightPaneX)
        assertTrue(geom.heroCoverY >= geom.usableTop)
        assertTrue(geom.heroCoverY + geom.heroCoverSize <= geom.usableBottom)
        assertTrue(geom.lyricsCompactCoverY + geom.lyricsCompactCoverSize < geom.usableBottom)
        assertTrue(geom.controlStackTop >= geom.usableTop)
        assertTrue(geom.controlStackTop + geom.controlStackHeight <= geom.usableBottom + .5.dp)
        assertTrue(geom.controlsScale >= 0.76f)
    }

    @Test fun portraitControlColumnMatchesOuterMarginEvenInCompactSmallWindow() {
        val margin = 20.dp
        val (x, width) = portraitControlColumnRange(width = 280.dp, outerMargin = margin)
        assertEquals(20f, x.value, 0.001f)
        assertEquals(240f, width.value, 0.001f)
        assertEquals(280f, (x * 2 + width).value, 0.001f)
    }

    @Test fun immersiveCoverGeometryFillsTopWidthAndFeathersBehindMetadata() {
        val portrait = computeImmersiveCoverGeometry(
            width = 412.dp,
            height = 915.dp,
            statusTop = 36.dp,
            seekCeiling = 637.dp,
            titleToSeek = 72.dp,
            compactWindow = false,
        )
        assertEquals(0f, portrait.coverX.value, 0.001f)
        assertEquals(0f, portrait.coverY.value, 0.001f)
        assertEquals(412f, portrait.coverWidth.value, 0.001f)
        assertTrue(portrait.titleY > 36.dp)
        assertTrue(portrait.seekY >= portrait.titleY + 72.dp - 0.1.dp)
        assertTrue(portrait.seekY <= 637.dp)
        assertTrue(portrait.coverHeight > portrait.titleY)

        val compact = computeImmersiveCoverGeometry(
            width = 280.dp,
            height = 520.dp,
            statusTop = 24.dp,
            seekCeiling = 311.dp,
            titleToSeek = 48.dp,
            compactWindow = true,
        )
        assertEquals(0f, compact.coverX.value, 0.001f)
        assertEquals(0f, compact.coverY.value, 0.001f)
        assertEquals(280f, compact.coverWidth.value, 0.001f)
        assertTrue(compact.seekY <= 311.dp)
        assertTrue(compact.coverHeight > compact.titleY)
    }

    @Test fun immersiveCoverBottomCornersMotionFadeAndSpatialButtonSuppressionWorkAsDesigned() {
        assertEquals(13f, immersiveCoverBottomCornerRadius(13.dp, 9.dp, expansion = 0f, lyricsProgress = 0f).value, 0.001f)
        assertEquals(0f, immersiveCoverBottomCornerRadius(13.dp, 9.dp, expansion = 1f, lyricsProgress = 0f).value, 0.001f)
        assertEquals(9f, immersiveCoverBottomCornerRadius(13.dp, 9.dp, expansion = 1f, lyricsProgress = 1f).value, 0.001f)

        assertEquals(0f, computeMotionCoverOverlayAlpha(expansion = 0f, lyricsProgress = 0f, mixActive = false, spatialActive = false), 0.0001f)
        assertEquals(0f, computeMotionCoverOverlayAlpha(expansion = 0.12f, lyricsProgress = 0f, mixActive = false, spatialActive = false), 0.0001f)
        val mid = computeMotionCoverOverlayAlpha(expansion = 0.35f, lyricsProgress = 0f, mixActive = false, spatialActive = false)
        assertTrue("Expected mid fade in (0, 1), got $mid", mid > 0.05f && mid < 0.95f)
        assertEquals(1f, computeMotionCoverOverlayAlpha(expansion = 1f, lyricsProgress = 0f, mixActive = false, spatialActive = false), 0.0001f)
        assertEquals(0f, computeMotionCoverOverlayAlpha(expansion = 1f, lyricsProgress = 1f, mixActive = false, spatialActive = false), 0.0001f)
        assertEquals(0f, computeMotionCoverOverlayAlpha(expansion = 1f, lyricsProgress = 0f, mixActive = true, spatialActive = false), 0.0001f)
        assertEquals(0f, computeMotionCoverOverlayAlpha(expansion = 1f, lyricsProgress = 0f, mixActive = false, spatialActive = true), 0.0001f)

        assertTrue(shouldShowSpatialCoverButton(spatialCoverEnabled = true, appleMusicAnimatedCoverEnabled = false, motionVideoPath = null))
        assertTrue(shouldShowSpatialCoverButton(spatialCoverEnabled = true, appleMusicAnimatedCoverEnabled = true, motionVideoPath = null))
        assertFalse(shouldShowSpatialCoverButton(spatialCoverEnabled = true, appleMusicAnimatedCoverEnabled = true, motionVideoPath = "/cache/am/cover.mp4"))
        assertFalse(shouldShowSpatialCoverButton(spatialCoverEnabled = false, appleMusicAnimatedCoverEnabled = false, motionVideoPath = null))

        val landscape = computeLandscapePlayerGeometry(
            width = 860.dp,
            height = 412.dp,
            leftInset = 32.dp,
            topInset = 18.dp,
            rightInset = 16.dp,
            bottomInset = 20.dp,
            showVolume = true,
        )
        val landscapeImm = computeLandscapeImmersiveCoverGeometry(
            width = 860.dp,
            height = 412.dp,
            rightPaneX = landscape.rightPaneX,
            leftPaneWidth = landscape.leftPaneWidth,
        )
        assertEquals(0f, landscapeImm.coverX.value, 0.001f)
        assertEquals(0f, landscapeImm.coverY.value, 0.001f)
        assertEquals(412f, landscapeImm.coverHeight.value, 0.001f)
        assertTrue(landscapeImm.coverWidth >= landscape.rightPaneX)
        assertTrue(landscapeImm.coverWidth.value <= 860f * 0.60f + 0.01f)
    }
}
