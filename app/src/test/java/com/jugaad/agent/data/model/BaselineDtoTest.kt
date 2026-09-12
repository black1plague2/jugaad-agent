package com.jugaad.agent.data.model

import com.jugaad.agent.domain.model.Baseline
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineDtoTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun legacyJsonWithoutNewFieldsDecodesWithZeroDefaults() {
        val legacy = """
            {
              "schema": 1,
              "assetId": "a1",
              "capturedAtMs": 1000,
              "meanFeature": [0.1, 0.2],
              "spread": 0.5,
              "rawStd": 0.1,
              "imuIndexMean": 0.3,
              "clipCount": 3
            }
        """.trimIndent()

        val dto = json.decodeFromString<BaselineDto>(legacy)

        assertEquals(0.3, dto.imuIndexMean, 1e-9)
        assertEquals(3, dto.clipCount)
        assertEquals(0.0, dto.gyroIndexMean, 1e-9)
        assertEquals(0.0, dto.magIndexMean, 1e-9)
        assertEquals(0.0, dto.magRmsMean, 1e-9)

        val domain = dto.toDomain()
        assertEquals(0.0, domain.gyroIndexMean, 1e-9)
        assertEquals(0.0, domain.magIndexMean, 1e-9)
        assertEquals(0.0, domain.magRmsMean, 1e-9)
    }

    @Test
    fun roundTripPreservesNewFields() {
        val baseline = Baseline(
            assetId = "a1",
            capturedAtMs = 1000L,
            meanFeature = floatArrayOf(0.1f, 0.2f),
            spread = 0.5,
            rawStd = 0.1,
            imuIndexMean = 0.3,
            clipCount = 3,
            gyroIndexMean = 0.4,
            magIndexMean = 0.5,
            magRmsMean = 6.0,
        )
        val encoded = json.encodeToString(BaselineDto.from(baseline))
        val decoded = json.decodeFromString<BaselineDto>(encoded)

        assertEquals(baseline.gyroIndexMean, decoded.gyroIndexMean, 1e-9)
        assertEquals(baseline.magIndexMean, decoded.magIndexMean, 1e-9)
        assertEquals(baseline.magRmsMean, decoded.magRmsMean, 1e-9)
    }
}
