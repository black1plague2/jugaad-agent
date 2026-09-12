package com.jugaad.agent.data.model

import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.IssueSuggestion
import com.jugaad.agent.domain.model.MachineStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisDtoTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun legacyJsonWithoutNewFieldsDecodesWithDefaults() {
        val legacy = """
            {
              "schema": 1,
              "id": "d1",
              "assetId": "a1",
              "timestampMs": 1000,
              "anomalyScore": 1.5,
              "cosineDistance": 0.3,
              "spread": 0.1,
              "status": "WARNING",
              "imuIndex": 0.2,
              "dominantHz": 60.0
            }
        """.trimIndent()

        val dto = json.decodeFromString<DiagnosisDto>(legacy)

        assertEquals("acoustic", dto.dominantSource)
        assertEquals(0.0, dto.sensorScore, 1e-9)
        assertTrue(dto.issues.isEmpty())

        val domain = dto.toDomain()
        assertEquals("acoustic", domain.dominantSource)
        assertEquals(0.0, domain.sensorScore, 1e-9)
        assertTrue(domain.issues.isEmpty())
    }

    @Test
    fun roundTripPreservesNewFields() {
        val diagnosis = Diagnosis(
            id = "d1",
            assetId = "a1",
            timestampMs = 1000L,
            anomalyScore = 1.5,
            cosineDistance = 0.3,
            spread = 0.1,
            status = MachineStatus.WARNING,
            imuIndex = 0.2,
            dominantHz = 60.0,
            dominantSource = "accel",
            sensorScore = 0.75,
            issues = listOf(
                IssueSuggestion(
                    faultId = "pump_cavitation",
                    label = "Pump cavitation or low water",
                    confidence = 0.72f,
                    matchedKeywords = listOf("gurgling", "no water"),
                    action = "Check water supply and inlet filter; prime the pump.",
                    severity = "WARNING",
                ),
            ),
        )
        val encoded = json.encodeToString(DiagnosisDto.from(diagnosis))
        val decoded = json.decodeFromString<DiagnosisDto>(encoded)

        assertEquals(diagnosis.dominantSource, decoded.dominantSource)
        assertEquals(diagnosis.sensorScore, decoded.sensorScore, 1e-9)
        assertEquals(diagnosis.issues, decoded.toDomain().issues)
    }
}
