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
 * Uses Android's device-tuned view feedback APIs rather than fixed-duration
 * vibration waveforms. These calls respect the system touch-feedback setting.
 */
object MedVaultHaptics {
    enum class Kind { SELECTION, SOFT, LIGHT, MEDIUM, DETENT, SUCCESS, ERROR, WARNING }

    fun perform(view: View, kind: Kind) {
        val constant = when (kind) {
            Kind.SELECTION, Kind.DETENT -> HapticFeedbackConstants.CLOCK_TICK
            Kind.SOFT -> HapticFeedbackConstants.CONTEXT_CLICK
            Kind.LIGHT -> HapticFeedbackConstants.VIRTUAL_KEY
            Kind.MEDIUM -> HapticFeedbackConstants.LONG_PRESS
            Kind.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            Kind.ERROR -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Kind.WARNING -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

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
