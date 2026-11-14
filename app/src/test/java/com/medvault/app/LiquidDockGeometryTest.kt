package com.medvault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidDockGeometryTest {
    private fun frame(center: Float, progress: Float = 0f, compact: Boolean = false) =
        LiquidDockGeometryEngine.calculate(
            dockWidthPx = 360f,
            contentWidthPx = 320f,
            dockHeightPx = if (compact) 50f else 62f,
            requestedCenterX = center,
            progress = progress,
            velocityStretch = 0f,
            horizontalInsetPx = 5f,
            restingHeightPx = if (compact) 42f else 52f
        )

    @Test fun idleCapsuleHasStadiumAspectRatio() {
        val value = frame(180f)
        assertTrue(value.widthPx > value.heightPx * 1.58f - 0.01f)
    }

    @Test fun idleIndicatorStaysWithinVerticalDockBounds() {
        val value = frame(180f)
        assertTrue(value.heightPx <= 62f)
        assertEquals(31f, value.centerY, 0.001f)
    }

    @Test fun activeLensExpandsBeyondRestingDimensions() {
        val idle = frame(180f)
        val active = frame(180f, progress = 1f)
        assertTrue(active.widthPx > idle.widthPx)
        assertTrue(active.heightPx > idle.heightPx)
    }

    @Test fun homeLensIsClampedToLeftOuterWall() {
        val value = frame(-100f, progress = 1f)
        assertTrue(value.centerX - value.widthPx / 2f >= 5f - 0.001f)
    }

    @Test fun offlineLensIsClampedToRightOuterWall() {
        val value = frame(1000f, progress = 1f)
        assertTrue(value.centerX + value.widthPx / 2f <= 355f + 0.001f)
    }

    @Test fun compactModeUsesCompactHeight() {
        val value = frame(180f, compact = true)
        assertEquals(42f, value.heightPx, 0.001f)
        assertEquals(25f, value.centerY, 0.001f)
    }

    @Test fun liveIndexTracksLensCenter() {
        assertEquals(0, frame(45f, progress = 1f).liveActiveIndex)
        assertEquals(3, frame(315f, progress = 1f).liveActiveIndex)
    }
}
