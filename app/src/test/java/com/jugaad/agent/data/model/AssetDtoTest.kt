package com.jugaad.agent.data.model

import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.Sensitivity
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

    /** v16 hard requirement: a real-shaped pre-v16 asset.json (schema 1, no `sensitivity` key)
     *  must load as Standard with its stored t1/t2 exactly unchanged, and every existing field
     *  must survive the round trip - a device in the field must not have its thresholds moved
     *  by an app update alone. */
    @Test
    fun realShapedLegacyAssetJsonLoadsAsStandardWithThresholdsUntouched() {
        val legacy = """
            {
              "schema": 1,
              "id": "coffee-01",
              "name": "Coffee vending machine",
              "createdAtMs": 1717000000000,
              "nameplatePhoto": "nameplate.jpg",
              "hasBaseline": true,
              "t1": 2.35,
              "t2": 5.1,
              "machineTypeId": "coffee_vending",
              "benchTest": false
            }
        """.trimIndent()

        val dto = json.decodeFromString<AssetDto>(legacy)
        val asset = dto.toDomain()

        assertEquals(Sensitivity.STANDARD, asset.sensitivity)
        assertEquals(2.35, asset.thresholds.t1, 1e-9)
        assertEquals(5.1, asset.thresholds.t2, 1e-9)
        assertEquals("coffee-01", asset.id)
        assertEquals("Coffee vending machine", asset.name)
        assertEquals(1717000000000L, asset.createdAtMs)
        assertEquals("nameplate.jpg", asset.nameplatePhoto)
        assertTrue(asset.hasBaseline)
        assertEquals("coffee_vending", asset.machineTypeId)
        assertFalse(asset.benchTest)

        // Re-serializing must not drop any field either.
        val reEncoded = json.decodeFromString<AssetDto>(json.encodeToString(dto))
        assertEquals(dto, reEncoded)
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
