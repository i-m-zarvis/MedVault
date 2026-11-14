package com.medvault.app

import androidx.compose.ui.graphics.Path
import kotlin.math.max

/**
 * Builds a horizontal stadium capsule. The top and bottom remain straight;
 * each end is a true semicircle rather than an oval Bézier approximation.
 */
internal object LiquidDockShape {
    fun updatePath(
        path: Path,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        direction: Float,
        trailingBias: Float
    ) {
        path.reset()

        val safeHeight = max(1f, height)
        val radius = safeHeight / 2f
        val safeWidth = max(safeHeight, width)
        val left = centerX - safeWidth / 2f
        val right = centerX + safeWidth / 2f
        val top = centerY - radius
        val bottom = centerY + radius
        val k = 0.55228475f

        path.moveTo(left + radius, top)
        path.lineTo(right - radius, top)

        // Right semicircular end.
        path.cubicTo(
            right - radius + radius * k, top,
            right, centerY - radius * k,
            right, centerY
        )
        path.cubicTo(
            right, centerY + radius * k,
            right - radius + radius * k, bottom,
            right - radius, bottom
        )

        path.lineTo(left + radius, bottom)

        // Left semicircular end.
        path.cubicTo(
            left + radius - radius * k, bottom,
            left, centerY + radius * k,
            left, centerY
        )
        path.cubicTo(
            left, centerY - radius * k,
            left + radius - radius * k, top,
            left + radius, top
        )
        path.close()
    }
}
