package com.medvault.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/**
 * Central semantic haptics for MedVault.
 *
 * Prefer Android's semantic feedback constants so the OS and device
 * manufacturer can tune the physical response. View feedback also respects
 * the user's system touch-feedback preference.
 */
object MedVaultHaptics {
    enum class Kind { SELECTION, SOFT, LIGHT, MEDIUM, DETENT, SUCCESS, ERROR, WARNING }

    fun perform(view: View, kind: Kind) {
        val constant = when (kind) {
            // API 34 adds dedicated segmented-control ticks. Older devices use
            // the closest native equivalents rather than custom vibration.
            Kind.SELECTION -> if (Build.VERSION.SDK_INT >= 34) {
                HapticFeedbackConstants.SEGMENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            Kind.DETENT -> if (Build.VERSION.SDK_INT >= 34) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            Kind.SOFT -> HapticFeedbackConstants.CONTEXT_CLICK
            Kind.LIGHT -> HapticFeedbackConstants.VIRTUAL_KEY
            // A gesture activation has a more deliberate semantic meaning
            // than a regular key tap; retain a legacy fallback.
            Kind.MEDIUM -> if (Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            Kind.WARNING -> HapticFeedbackConstants.LONG_PRESS
            Kind.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.CONTEXT_CLICK
            }
            Kind.ERROR -> if (Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
        view.performHapticFeedback(constant)
    }
}

/** Compose helper for outcome feedback after an operation actually completes. */
@Composable
fun rememberMedVaultHaptics(): (MedVaultHaptics.Kind) -> Unit {
    val view = LocalView.current
    return { kind -> MedVaultHaptics.perform(view, kind) }
}

/** Click surface with one centralised, system-respecting haptic. */
fun Modifier.medVaultClickable(
    enabled: Boolean = true,
    kind: MedVaultHaptics.Kind = MedVaultHaptics.Kind.LIGHT,
    onClick: () -> Unit
): Modifier = composed {
    val view = LocalView.current
    clickable(enabled = enabled) {
        MedVaultHaptics.perform(view, kind)
        onClick()
    }
}
