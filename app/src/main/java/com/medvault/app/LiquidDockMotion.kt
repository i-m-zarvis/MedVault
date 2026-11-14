package com.medvault.app

/** Explicit visual phases for the four-tab liquid dock. */
internal enum class DockPhase {
    Idle,
    Pressed,
    Dragging,
    Travelling,
    Settling
}

internal fun resolveDockPhase(
    isPressed: Boolean,
    isDragging: Boolean,
    isAnimating: Boolean
): DockPhase = when {
    isDragging -> DockPhase.Dragging
    isPressed -> DockPhase.Pressed
    isAnimating -> DockPhase.Travelling
    else -> DockPhase.Idle
}
