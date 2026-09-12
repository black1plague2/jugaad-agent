package com.jugaad.agent.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppConfigTest {

    private fun assetText(name: String): String {
        val candidates = listOf(
            File("app/src/main/assets/config/$name"),
            File("src/main/assets/config/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("could not locate asset $name; tried ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private val defaultsText by lazy { assetText("app_config.json") }

    @Test
    fun defaultsEqualTheAssetValues() {
        val parsed = ConfigStore.parse(defaultsText)
        assertEquals(AppConfig(), parsed)
    }

    @Test
    fun partialOverrideMergeOnlyChangesGivenKeys() {
        val overrideJson = """{"thresholds":{"t1":9.0},"sync":{"port":9999}}"""
        val merged = ConfigStore.merge(defaultsText, overrideJson)

        assertEquals(9.0, merged.thresholds.t1, 1e-9)
        // untouched sibling key in the same section keeps its default.
        assertEquals(AppConfig().thresholds.t2, merged.thresholds.t2, 1e-9)
        assertEquals(9999, merged.sync.port)
        // untouched section is unaffected entirely.
        assertEquals(AppConfig().sensors, merged.sensors)
    }

    @Test
    fun policyOfThenApplyPolicyRoundTrips() {
        ConfigStore.loadFrom(defaultsText, overridesText = null)

        val changed = AppConfig(
            thresholds = Thresholds(t1 = 5.5, t2 = 8.0, spreadFloor = 0.03),
            sensors = Sensors(zWeights = ZWeights(accel = 0.7, gyro = 0.6, mag = 0.4, magRms = 0.4)),
            sync = Sync(retryBackoffMs = listOf(1000, 2000)),
        )

        val policy = ConfigStore.policyOf(changed)
        val didChange = ConfigStore.applyPolicy(policy)

        assertTrue(didChange)
        val effective = ConfigStore.effective.value
        assertEquals(changed.thresholds, effective.thresholds)
        assertEquals(changed.sensors, effective.sensors)
        assertEquals(changed.sync.retryBackoffMs, effective.sync.retryBackoffMs)
        // budget was never part of the policy, so it stays at whatever it already was.
        assertEquals(AppConfig().budget, effective.budget)
    }

    @Test
    fun budgetIsNeverInPolicy() {
        val cfg = AppConfig(budget = Budget(maxThreads = 1, thermalMax = 3, minBatteryPct = 50))
        val policy = ConfigStore.policyOf(cfg)

        assertTrue(policy.keys.none { it == "budget" || it.startsWith("budget.") })

        ConfigStore.loadFrom(defaultsText, overridesText = null)
        val changed = ConfigStore.applyPolicy(mapOf("budget.maxThreads" to "1"))

        assertFalse(changed)
        assertEquals(AppConfig().budget.maxThreads, ConfigStore.effective.value.budget.maxThreads)
    }

    @Test
    fun sourceReportsDefaultOverrideAndPolicy() {
        val overrideJson = """{"sensors":{"zFloor":0.05}}"""
        ConfigStore.loadFrom(defaultsText, overridesText = overrideJson)

        assertEquals(ConfigSource.DEFAULT, ConfigStore.source("thresholds.t1"))
        assertEquals(ConfigSource.OVERRIDE, ConfigStore.source("sensors.zFloor"))

        ConfigStore.applyPolicy(mapOf("thresholds.t1" to "9.0"))
        assertEquals(ConfigSource.POLICY, ConfigStore.source("thresholds.t1"))
        // policy did not touch sensors.zFloor, so its override provenance still stands.
        assertEquals(ConfigSource.OVERRIDE, ConfigStore.source("sensors.zFloor"))
    }

    @Test
    fun applyPolicyIgnoresUnknownKeysAndWrongTypes() {
        ConfigStore.loadFrom(defaultsText, overridesText = null)

        val changed = ConfigStore.applyPolicy(
            mapOf(
                "thresholds.doesNotExist" to "1.0",
                "calibration.auto" to "not-a-boolean",
            ),
        )

        assertFalse(changed)
        assertEquals(AppConfig(), ConfigStore.effective.value)
    }
}
