package com.jugaad.agent.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Raw acoustic capture.
 *
 *  - 44.1 kHz, mono, 16-bit PCM
 *  - AudioSource.UNPROCESSED so the OS does not apply AGC / noise-suppression /
 *    echo-cancel (all of which would destroy the machine's spectral signature).
 *    Falls back to VOICE_RECOGNITION on devices that don't honour UNPROCESSED.
 *  - Exactly [Constants.CAPTURE_SAMPLES] (132300) samples are returned as floats in [-1, 1].
 */
class AudioCapture {

    data class Clip(val samples: FloatArray, val source: String)

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(
        seconds: Int = Constants.CAPTURE_SECONDS,
        onProgress: (fraction: Float, latestChunk: ShortArray) -> Unit = { _, _ -> },
    ): Outcome<Clip> = withContext(Dispatchers.Default) {
        val totalSamples = Constants.SAMPLE_RATE_HZ * seconds
        val minBuf = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return@withContext Outcome.Err("AudioRecord.getMinBufferSize returned $minBuf")

        val bufBytes = maxOf(minBuf, Constants.SAMPLE_RATE_HZ) // >= ~0.5 s headroom

        var record: AudioRecord? = null
        var usedSource = "UNPROCESSED"
        try {
            record = buildRecord(MediaRecorder.AudioSource.UNPROCESSED, bufBytes)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                usedSource = "VOICE_RECOGNITION"
                record = buildRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufBytes)
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Outcome.Err("AudioRecord failed to initialise (source=$usedSource)")
            }
            Logx.i("AudioCapture start source=$usedSource sr=${Constants.SAMPLE_RATE_HZ} target=$totalSamples")

            val out = ShortArray(totalSamples)
            val chunk = ShortArray(minBuf / 2)
            var read = 0
            record.startRecording()
            while (read < totalSamples) {
                coroutineContext.ensureActive()
                val want = minOf(chunk.size, totalSamples - read)
                val n = record.read(chunk, 0, want)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        return@withContext Outcome.Err("AudioRecord.read error code $n")
                    }
                    continue
                }
                System.arraycopy(chunk, 0, out, read, n)
                read += n
                onProgress(read.toFloat() / totalSamples, chunk.copyOf(n))
            }
            record.stop()

            val floats = FloatArray(totalSamples) { out[it] / 32768f }
            Logx.i("AudioCapture done samples=${floats.size}")
            Outcome.Ok(Clip(floats, usedSource))
        } catch (t: Throwable) {
            Outcome.Err("AudioCapture: ${t.message ?: t::class.java.simpleName}", t)
        } finally {
            runCatching { record?.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(source: Int, bufBytes: Int) = AudioRecord(
        source,
        Constants.SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufBytes,
    )
}
