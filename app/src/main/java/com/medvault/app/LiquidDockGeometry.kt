package com.medvault.app

import kotlin.math.abs
import kotlin.math.min

/**
 * Shared pixel-space geometry for the four-slot liquid navigation dock.
 * All dimensions are uniform: the capsule is always drawn as a true stadium.
 */
internal data class DockGeometryFrame(
    val slotWidthPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val centerX: Float,
    val centerY: Float,
    val liveActiveIndex: Int
)

internal object LiquidDockGeometryEngine {
    fun calculate(
        dockWidthPx: Float,
        contentWidthPx: Float,
        dockHeightPx: Float,
        requestedCenterX: Float,
        progress: Float,
        velocityStretch: Float,
        horizontalInsetPx: Float,
        restingHeightPx: Float
    ): DockGeometryFrame {
        val slotWidth = (contentWidthPx / 4f).coerceAtLeast(1f)
        val p = progress.coerceIn(0f, 1f)
        val idleHeight = restingHeightPx.coerceAtMost(dockHeightPx)
        val idleWidth = (slotWidth * 0.84f).coerceAtLeast(idleHeight * 1.58f)
        val activeHeight = dockHeightPx * 1.12f
        val activeWidth = slotWidth * 1.14f

        val height = lerp(idleHeight, activeHeight, p)
        val requestedWidth = lerp(idleWidth, activeWidth, p) *
            (1f + velocityStretch.coerceIn(0f, 0.18f) * p)

        // Only the outer dock walls constrain horizontal growth; neighbouring
        // tab slots are intentionally allowed to be covered by the liquid lens.
        val maxWidth = (dockWidthPx - horizontalInsetPx * 2f).coerceAtLeast(1f)
        val width = min(requestedWidth, maxWidth)
        val minCenter = horizontalInsetPx + width / 2f
        val maxCenter = (dockWidthPx - horizontalInsetPx - width / 2f)
            .coerceAtLeast(minCenter)
        val center = requestedCenterX.coerceIn(minCenter, maxCenter)

        val liveIndex = (0..3).minByOrNull { index ->
            abs(center - (horizontalInsetPx + slotWidth * (index + 0.5f)))
        } ?: 0

        return DockGeometryFrame(
            slotWidthPx = slotWidth,
            widthPx = width,
            heightPx = height,
            centerX = center,
            centerY = dockHeightPx / 2f,
            liveActiveIndex = liveIndex
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
