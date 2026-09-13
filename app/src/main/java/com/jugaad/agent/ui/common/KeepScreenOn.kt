package com.jugaad.agent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the display on for as long as [keepOn] is true. Used while the get-ready countdown and
 * the recording that follows are on screen, since the phone is lying face up on the machine and
 * has no touch input to keep it from sleeping mid-recording.
 */
@Composable
fun KeepScreenOn(keepOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}
