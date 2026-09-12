package com.jugaad.agent.core.config

import kotlinx.serialization.Serializable

/**
 * Every tunable that used to be a Kotlin constant, as one typed tree.
 *
 * Kotlin defaults below MUST equal `assets/config/app_config.json` so that a
 * partial override or policy JSON (missing most keys) still decodes into a full
 * config via [ConfigStore]. See the v4 plan, "App configuration", for the layering
 * (asset defaults -> filesDir/config/overrides.json -> network policy) and the
 * source of truth for every default.
 */
@Serializable
data class AppConfig(
    val thresholds: Thresholds = Thresholds(),
    val calibration: Calibration = Calibration(),
    val sensors: Sensors = Sensors(),
    val promotion: Promotion = Promotion(),
    val acceptGuard: AcceptGuard = AcceptGuard(),
    val training: Training = Training(),
    val recipes: Recipes = Recipes(),
    val sync: Sync = Sync(),
    val sharing: Sharing = Sharing(),
    /** Device-local. Never included in [ConfigStore.policyOf] / accepted by [ConfigStore.applyPolicy]. */
    val budget: Budget = Budget(),
    val ranking: Ranking = Ranking(),
    val diagnosis: Diagnosis = Diagnosis(),
    val features: Features = Features(),
)

@Serializable
data class Thresholds(
    val t1: Double = 2.0,
    val t2: Double = 4.0,
    val spreadFloor: Double = 0.02,
)

@Serializable
data class Calibration(
    val auto: Boolean = true,
    val k1: Double = 3.0,
    val k2: Double = 6.0,
    val minHealthy: Int = 5,
    val healthyQuantile: Double = 0.9,
    val driftWindow: Int = 8,
    val driftRatio: Double = 0.5,
    val autoRefreshBaseline: Boolean = true,
    val refreshMinHealthy: Int = 6,
)

@Serializable
data class ZWeights(
    val accel: Double = 1.0,
    val gyro: Double = 0.8,
    val mag: Double = 0.5,
    val magRms: Double = 0.5,
)

@Serializable
data class Sensors(
    val zWeights: ZWeights = ZWeights(),
    val zFloor: Double = 0.02,
    val sensorScoreScale: Double = 0.5,
)

@Serializable
data class Promotion(
    val margin: Double = 0.03,
    val winsRequired: Int = 2,
    val minNVal: Int = 8,
    val historyLen: Int = 8,
)

@Serializable
data class AcceptGuard(
    val minTrain: Int = 5,
    val maxDrop: Double = 0.10,
)

@Serializable
data class Training(
    val maxEpochs: Int = 30,
    val fixedEpochs: Int = 10,
    val patience: Int = 5,
    val minVal: Int = 4,
    val batch: Int = 8,
)

@Serializable
data class Recipes(
    val noiseSigma: Double = 0.15,
    val distillAlpha: Double = 0.99,
    val distillMinConf: Double = 0.9,
    val uncertainTrigger: Double = 0.6,
    val uncertainFloor: Double = 0.1,
)

@Serializable
data class Sync(
    val port: Int = 8988,
    val windowMs: Int = 5000,
    val connectTimeoutMs: Int = 5000,
    val readTimeoutMs: Int = 30000,
    val schedulerMinutes: Int = 15,
    val staleMinutes: Int = 30,
    val retryBackoffMs: List<Int> = listOf(2000, 5000, 15000),
    val failoverAfterFailures: Int = 3,
    val autoFailover: Boolean = true,
)

@Serializable
data class Sharing(
    val enabled: Boolean = true,
    val maxPoolSamples: Int = 5000,
    val maxPerOrigin: Int = 1500,
    val batchSize: Int = 200,
)

/** Device-local (thermal/battery/CPU budget). Never replicated as policy. */
@Serializable
data class Budget(
    val maxThreads: Int = 4,
    val thermalMax: Int = 2, // PowerManager.THERMAL_STATUS_MODERATE
    val minBatteryPct: Int = 15,
)

@Serializable
data class Ranking(
    val costPerMs: Double = 0.002,
    val unlabelledBonus: Double = 2.0,
)

@Serializable
data class Diagnosis(
    val minConfidence: Double = 0.5,
    val maxIssues: Int = 3,
)

@Serializable
data class BandGroups(
    val low: List<Double> = listOf(20.0, 150.0),
    val mid: List<Double> = listOf(150.0, 1200.0),
    val high: List<Double> = listOf(1200.0, 6000.0),
    val veryHigh: List<Double> = listOf(6000.0, 11000.0),
)

@Serializable
data class Features(
    val schemaVersion: Int = 3,
    val bandGroups: BandGroups = BandGroups(),
    val lineHz: List<Double> = listOf(50.0, 100.0),
)

/** Where an effective config value at a given "section.key" path currently comes from. */
enum class ConfigSource { DEFAULT, OVERRIDE, POLICY }
