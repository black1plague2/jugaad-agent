package com.jugaad.agent.ml.diagnosis

import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.core.config.Condition
import com.jugaad.agent.core.config.Diagnosis
import com.jugaad.agent.core.config.FaultRule
import com.jugaad.agent.core.config.MachineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTest {

    private fun fault(id: String, vararg conditions: Condition) = FaultRule(
        id = id,
        label = id.replace('_', ' '),
        keywords = listOf("${id}_kw1", "${id}_kw2"),
        evidence = conditions.toList(),
        action = "$id action",
        severity = "WARNING",
    )

    private val type = MachineType(
        id = "test_machine",
        label = "Test machine",
        keywords = emptyList(),
        expectedRotationHz = listOf(0.0, 60.0),
        notes = "",
        faults = listOf(
            fault(
                "fault_a",
                Condition("lowBandDelta", ">", 0.5, 1.0),
                Condition("cnnClass", "==", 1.0, 1.0),
            ),
            fault(
                "fault_b",
                Condition("highBandDelta", ">", 0.5, 1.0),
            ),
        ),
    )

    @Test
    fun matchingFaultIsSuggestedFirstWithCorrectConfidence() {
        val evidence = mapOf("lowBandDelta" to 0.9, "cnnClass" to 1.0, "highBandDelta" to 0.0)
        val issues = RulesEngine.evaluate(type, evidence, AppConfig())

        assertEquals(1, issues.size)
        assertEquals("fault_a", issues[0].faultId)
        assertEquals(1.0f, issues[0].confidence, 1e-6f)
        assertEquals(listOf("fault_a_kw1", "fault_a_kw2"), issues[0].matchedKeywords)
        assertEquals("WARNING", issues[0].severity)
    }

    @Test
    fun healthyLikeEvidenceSuggestsNothing() {
        val evidence = mapOf("lowBandDelta" to 0.0, "highBandDelta" to 0.0, "cnnClass" to -1.0)
        val issues = RulesEngine.evaluate(type, evidence, AppConfig())
        assertTrue(issues.isEmpty())
    }

    @Test
    fun minConfidenceIsRespected() {
        // Only one of fault_a's two equally-weighted conditions is satisfied -> confidence 0.5.
        val evidence = mapOf("lowBandDelta" to 0.9, "cnnClass" to 0.0, "highBandDelta" to 0.0)

        val loose = RulesEngine.evaluate(type, evidence, AppConfig(diagnosis = Diagnosis(minConfidence = 0.5)))
        assertEquals(1, loose.size)
        assertEquals(0.5f, loose[0].confidence, 1e-6f)

        val strict = RulesEngine.evaluate(type, evidence, AppConfig(diagnosis = Diagnosis(minConfidence = 0.6)))
        assertTrue(strict.isEmpty())
    }

    @Test
    fun maxIssuesIsRespected() {
        val threeFaultType = type.copy(
            faults = listOf(
                fault("f1", Condition("anomalyScore", ">", 0.0, 1.0)),
                fault("f2", Condition("anomalyScore", ">", 0.0, 1.0)),
                fault("f3", Condition("anomalyScore", ">", 0.0, 1.0)),
            ),
        )
        val evidence = mapOf("anomalyScore" to 5.0)

        val issues = RulesEngine.evaluate(threeFaultType, evidence, AppConfig(diagnosis = Diagnosis(maxIssues = 2)))
        assertEquals(2, issues.size)
    }
}
