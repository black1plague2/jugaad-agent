package com.jugaad.agent.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MachineCatalogTest {

    private fun assetText(name: String): String {
        val candidates = listOf(
            File("app/src/main/assets/config/$name"),
            File("src/main/assets/config/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("could not locate asset $name; tried ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private val types by lazy { MachineCatalog.parse(assetText("machines.json")) }

    private val expectedIds = listOf(
        "coffee_vending", "desk_fan", "ceiling_fan", "exhaust_fan", "ac_outdoor_unit",
        "refrigerator_compressor", "water_pump", "washing_machine", "air_compressor",
        "diesel_generator", "printer_3d", "server_rack_fan", "generic",
    )

    @Test
    fun allThirteenTypesArePresent() {
        assertEquals(13, types.size)
        assertEquals(expectedIds.toSet(), types.map { it.id }.toSet())
    }

    @Test
    fun everyRuleMetricAndOpIsValid() {
        for (type in types) {
            for (fault in type.faults) {
                for (cond in fault.evidence) {
                    assertTrue("${type.id}/${fault.id} metric ${cond.metric}", cond.metric in MachineCatalog.VALID_METRICS)
                    assertTrue("${type.id}/${fault.id} op ${cond.op}", cond.op in MachineCatalog.VALID_OPS)
                }
            }
        }
    }

    @Test
    fun everyTypeHasAtLeastThreeFaultsEachFullySpecified() {
        for (type in types) {
            assertTrue("${type.id} has ${type.faults.size} faults", type.faults.size >= 3)
            for (fault in type.faults) {
                assertTrue("${type.id}/${fault.id} needs >=2 conditions", fault.evidence.size >= 2)
                assertTrue("${type.id}/${fault.id} needs an action", fault.action.isNotBlank())
                assertTrue(
                    "${type.id}/${fault.id} severity must be WARNING or CRITICAL",
                    fault.severity == "WARNING" || fault.severity == "CRITICAL",
                )
            }
        }
    }

    @Test
    fun parseDropsRulesWithInvalidMetricOrOp() {
        val corrupt = """
            {"schemaVersion":1,"types":[
              {"id":"t1","label":"T1","faults":[
                {"id":"f1","label":"F1","action":"do it","severity":"WARNING","evidence":[
                  {"metric":"lowBandDelta","op":">","value":0.5,"weight":1.0},
                  {"metric":"notARealMetric","op":">","value":0.5,"weight":1.0},
                  {"metric":"peakiness","op":"~=","value":0.5,"weight":1.0}
                ]}
              ]}
            ]}
        """.trimIndent()

        val parsed = MachineCatalog.parse(corrupt)
        val fault = parsed.single().faults.single()

        assertEquals(1, fault.evidence.size)
        assertEquals("lowBandDelta", fault.evidence.single().metric)
    }

    @Test
    fun searchFiltraReturnsCoffeeVendingFirst() {
        MachineCatalog.primeForTest(types)
        val results = MachineCatalog.search("filtra")

        assertFalse(results.isEmpty())
        assertEquals("coffee_vending", results.first().id)
    }

    @Test
    fun blankSearchReturnsGenericFirst() {
        MachineCatalog.primeForTest(types)
        val results = MachineCatalog.search("")

        assertEquals(MachineCatalog.GENERIC_ID, results.first().id)
        assertEquals(types.size, results.size)
    }

    @Test
    fun byIdFallsBackToGeneric() {
        MachineCatalog.primeForTest(types)

        assertEquals("coffee_vending", MachineCatalog.byId("coffee_vending").id)
        assertEquals(MachineCatalog.GENERIC_ID, MachineCatalog.byId("no_such_machine_type").id)
    }
}
