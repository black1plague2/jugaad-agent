package com.jugaad.agent.fl

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Little-endian float32 wire/file encoding for weight vectors — no header,
 * just the variant's weightCount floats back to back. Shared by
 * `fl/weights_<id>.bin` and the WifiDirect sync protocol's weight payload.
 */
object WeightsCodec {
    fun encode(w: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in w) buf.putFloat(f)
        return buf.array()
    }

    fun decode(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }
}
