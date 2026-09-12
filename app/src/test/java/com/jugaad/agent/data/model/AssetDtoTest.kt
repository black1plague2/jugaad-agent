package com.jugaad.agent.data.model

import com.jugaad.agent.domain.model.Asset
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDtoTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun legacyJsonWithoutBenchTestDecodesAsFalse() {
        val legacy = """
            {
              "schema": 1,
              "id": "a1",
              "name": "Exhaust Fan 3",
              "createdAtMs": 1000
            }
        """.trimIndent()

        val dto = json.decodeFromString<AssetDto>(legacy)

        assertFalse(dto.benchTest)
        assertFalse(dto.toDomain().benchTest)
    }

    @Test
    fun roundTripPreservesBenchTest() {
        val asset = Asset(
            id = "a1",
            name = "Test rig",
            createdAtMs = 1000L,
            benchTest = true,
        )
        val encoded = json.encodeToString(AssetDto.from(asset))
        val decoded = json.decodeFromString<AssetDto>(encoded)

        assertTrue(decoded.benchTest)
        assertEquals(asset.benchTest, decoded.toDomain().benchTest)
    }
}
