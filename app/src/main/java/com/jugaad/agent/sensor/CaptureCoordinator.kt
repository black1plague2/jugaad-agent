package com.jugaad.agent.sensor

import android.Manifest
import androidx.annotation.RequiresPermission
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Everything one 3-second capture produces. */
data class RawCapture(
    val audio: FloatArray,          // 132300 samples, [-1, 1]
    val audioSource: String,
    val motion: MotionCapture.Reading,
)

/** Live state for the capture overlay (countdown ring + waveform). */
data class CaptureProgress(
    val running: Boolean = false,
    val fraction: Float = 0f,
    val secondsLeft: Int = Constants.CAPTURE_SECONDS,
    /** ~128 downsampled peak values in [0, 1] for the scrolling waveform. */
    val waveform: FloatArray = FloatArray(WAVE_BINS),
) {
    companion object { const val WAVE_BINS = 128 }
}

/**
 * Runs [AudioCapture] and [MotionCapture] together so the acoustic and
 * vibration views describe the same 3 seconds of machine behaviour.
 */
class CaptureCoordinator(
    private val audioCapture: AudioCapture,
    private val motionCapture: MotionCapture,
) {
    private val _progress = MutableStateFlow(CaptureProgress())
    val progress: StateFlow<CaptureProgress> = _progress.asStateFlow()

    private val waveform = FloatArray(CaptureProgress.WAVE_BINS)
    private var wavePos = 0

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun capture(seconds: Int = Constants.CAPTURE_SECONDS): Outcome<RawCapture> = coroutineScope {
        reset(seconds)
        _progress.value = _progress.value.copy(running = true)

        val motionJob = async { motionCapture.capture(seconds) }
        val audioJob = async {
            audioCapture.record(seconds) { fraction, chunk ->
                pushWaveform(chunk)
                _progress.value = _progress.value.copy(
                    fraction = fraction,
                    secondsLeft = (seconds - (fraction * seconds)).toInt().coerceIn(0, seconds),
                    waveform = waveform.copyOf(),
                )
            }
        }

        val audio = audioJob.await()
        val motion = motionJob.await()
        _progress.value = _progress.value.copy(running = false, fraction = 1f, secondsLeft = 0)

        when (audio) {
            is Outcome.Err -> audio
            is Outcome.Ok -> Outcome.Ok(RawCapture(audio.value.samples, audio.value.source, motion))
        }
    }

    private fun reset(seconds: Int) {
        waveform.fill(0f); wavePos = 0
        _progress.value = CaptureProgress(secondsLeft = seconds)
    }

    /** Fold a PCM chunk into the ring buffer as absolute peaks. */
    private fun pushWaveform(chunk: ShortArray) {
        if (chunk.isEmpty()) return
        val perBin = maxOf(1, chunk.size / 8)
        var i = 0
        while (i < chunk.size) {
            var peak = 0f
            var j = 0
            while (j < perBin && i + j < chunk.size) {
                val v = abs(chunk[i + j].toInt()) / 32768f
                if (v > peak) peak = v
                j++
            }
            waveform[wavePos] = peak
            wavePos = (wavePos + 1) % waveform.size
            i += perBin
        }
    }
}
