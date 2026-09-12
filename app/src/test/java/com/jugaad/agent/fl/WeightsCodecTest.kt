package com.jugaad.agent.fl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightsCodecTest {

    @Test
    fun roundTripPreservesValues() {
        val w = FloatArray(10) { it * 0.5f - 2f }
        val decoded = WeightsCodec.decode(WeightsCodec.encode(w))
        assertArrayEquals(w, decoded, 1e-6f)
    }

    @Test
    fun encodingIsLittleEndian() {
        // 1.0f is IEEE-754 0x3F800000; little-endian byte order: 00 00 80 3F.
        val bytes = WeightsCodec.encode(floatArrayOf(1.0f))
        assertEquals(4, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
        assertEquals(0x80, bytes[2].toInt() and 0xFF)
        assertEquals(0x3F, bytes[3].toInt() and 0xFF)
    }
}
