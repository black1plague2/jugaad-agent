package com.jugaad.agent.domain.usecase

/**
 * Renders a log-mel image to PNG bytes. Implemented in the `viz` layer with
 * android.graphics so the domain stays framework-free.
 */
fun interface SpectrogramPngRenderer {
    /** @param logMel row-major, mel-major, length nMels*frames. */
    fun render(logMel: FloatArray, nMels: Int, frames: Int, outWidth: Int, outHeight: Int): ByteArray
}
