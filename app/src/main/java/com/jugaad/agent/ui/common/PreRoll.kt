package com.jugaad.agent.ui.common

import com.jugaad.agent.core.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Get-ready countdown run before any user-started recording. Ticks 3, 2, 1 one second apart with
 * no sensor or microphone access, then invokes [start]'s callback which performs the actual
 * capture. Cancelling (Cancel button, back press, or leaving the screen) stops the coroutine
 * before the callback runs, so nothing is captured.
 *
 * [scope] should be the ViewModel's viewModelScope, or a rememberCoroutineScope() tied to the
 * composable, so the countdown is always cancelled when the screen goes away.
 */
class PreRoll(private val scope: CoroutineScope, private val tag: String) {
    private val _secondsLeft = MutableStateFlow<Int?>(null)

    /** Non-null while the countdown is showing; null once capture has started or been cancelled. */
    val secondsLeft: StateFlow<Int?> = _secondsLeft

    private var job: Job? = null

    fun start(onReady: suspend () -> Unit) {
        if (job?.isActive == true) return
        Logx.i("preroll: start ($tag)")
        job = scope.launch {
            for (s in 3 downTo 1) {
                _secondsLeft.value = s
                delay(1000)
            }
            _secondsLeft.value = null
            Logx.i("preroll: done, capture starting ($tag)")
            onReady()
        }
    }

    /** Aborts the countdown. A no-op, and silent, once the countdown has already finished. */
    fun cancel() {
        if (job?.isActive == true) {
            Logx.i("preroll: cancelled ($tag)")
            job?.cancel()
        }
        job = null
        _secondsLeft.value = null
    }
}
