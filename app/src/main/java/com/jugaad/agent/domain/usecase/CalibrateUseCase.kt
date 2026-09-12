package com.jugaad.agent.domain.usecase

import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.Outcome
import com.jugaad.agent.core.config.AppConfig
import com.jugaad.agent.domain.repository.AssetRepository
import com.jugaad.agent.fl.SampleStore
import com.jugaad.agent.ml.anomaly.Thresholds
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Turns this asset's own labelled anomaly scores into calibrated Warning/Critical
 * boundaries, replacing the DEFAULT_T1/T2 guess with numbers observed on this
 * specific machine. Robust median/MAD math lives in [CalibrationMath] so it stays
 * unit-testable without a [SampleStore].
 */
class CalibrateUseCase(
    private val assets: AssetRepository,
    private val store: SampleStore,
    private val cfg: () -> AppConfig,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Computes the proposed calibration without writing anything, or null if not enough data yet. */
    suspend fun observe(assetId: String): CalibrationRecord? {
        val c = cfg().calibration
        val samples = store.labelled().filter { it.assetId == assetId }
        val healthySamples = samples.filter { it.label == 0 }
        val healthy = healthySamples.mapNotNull { it.score }
        val faulty = samples.filter { it.label == 1 || it.label == 2 }.mapNotNull { it.score }

        val stats = CalibrationMath.compute(healthy, faulty, c.k1, c.k2, c.minHealthy) ?: return null

        val recentHealthy = healthySamples.sortedByDescending { it.ts }.take(c.driftWindow).mapNotNull { it.score }
        val drift = CalibrationMath.driftDetected(recentHealthy, stats.t1, c.driftRatio)

        return CalibrationRecord(
            t1 = stats.t1,
            t2 = stats.t2,
            method = "robust",
            nHealthy = stats.nHealthy,
            nFaulty = stats.nFaulty,
            medianHealthy = stats.medianHealthy,
            madHealthy = stats.madHealthy,
            drift = drift,
            lastRunMs = System.currentTimeMillis(),
        )
    }

    /** Computes and persists the calibration on the asset (thresholds + `assets/<id>/calibration.json`). */
    suspend fun apply(assetId: String): Outcome<CalibrationRecord> {
        val record = observe(assetId)
            ?: return Outcome.Err("Need at least ${cfg().calibration.minHealthy} labelled-healthy samples to calibrate")
        assets.updateThresholds(assetId, Thresholds.safe(record.t1, record.t2))
        writeRecord(assetId, record)
        Logx.i(
            "calibrated asset=$assetId t1=${"%.3f".format(record.t1)} t2=${"%.3f".format(record.t2)} " +
                "nHealthy=${record.nHealthy} nFaulty=${record.nFaulty} drift=${record.drift}"
        )
        return Outcome.Ok(record)
    }

    /** Runs [apply] when `calibration.auto` is on; a silent no-op otherwise or when data is insufficient. */
    suspend fun autoRun(assetId: String) {
        if (!cfg().calibration.auto) return
        apply(assetId)
    }

    private fun writeRecord(assetId: String, record: CalibrationRecord) {
        runCatching {
            val file = assets.resolve(assetId, "calibration.json")
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(record))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Logx.w("failed to write calibration.json for $assetId", it) }
    }
}

/** Per-asset adaptive calibration, persisted at `assets/<id>/calibration.json`. */
@Serializable
data class CalibrationRecord(
    val t1: Double,
    val t2: Double,
    val method: String,
    val nHealthy: Int,
    val nFaulty: Int,
    val medianHealthy: Double,
    val madHealthy: Double,
    val drift: Boolean,
    val lastRunMs: Long,
)

/**
 * Pure calibration math (no I/O) so it's unit-testable without a SampleStore.
 *
 *  - Robust stats on healthy scores: `T1 = median + k1 * MAD`, `T2 = median + k2 * MAD`
 *    (MAD floored at [MAD_FLOOR] so a tight healthy cluster can't collapse the bands).
 *  - If faulty samples exist, `T1` is pulled down to the midpoint of the worst healthy
 *    and best faulty score when that midpoint sits above the median (tightening the
 *    Warning boundary around real fault evidence), and `T2` is pushed up to the midpoint
 *    of `T1` and the worst faulty score. Either way `T2 >= 1.5 * T1` always holds.
 *  - Needs >= `minHealthy` healthy scores; returns null otherwise.
 */
object CalibrationMath {
    const val MAD_FLOOR = 0.05

    data class Stats(
        val t1: Double,
        val t2: Double,
        val medianHealthy: Double,
        val madHealthy: Double,
        val nHealthy: Int,
        val nFaulty: Int,
    )

    fun compute(
        healthyScores: List<Double>,
        faultyScores: List<Double>,
        k1: Double,
        k2: Double,
        minHealthy: Int,
    ): Stats? {
        if (healthyScores.size < minHealthy) return null

        val median = median(healthyScores)
        val mad = median(healthyScores.map { kotlin.math.abs(it - median) }).coerceAtLeast(MAD_FLOOR)
        var t1 = median + k1 * mad
        var t2 = median + k2 * mad

        if (faultyScores.isNotEmpty()) {
            val midpoint1 = (healthyScores.max() + faultyScores.min()) / 2.0
            if (midpoint1 > median) t1 = minOf(t1, midpoint1)
            val midpoint2 = (t1 + faultyScores.max()) / 2.0
            t2 = maxOf(t2, midpoint2)
        }
        t2 = maxOf(t2, 1.5 * t1)

        return Stats(
            t1 = t1,
            t2 = t2,
            medianHealthy = median,
            madHealthy = mad,
            nHealthy = healthyScores.size,
            nFaulty = faultyScores.size,
        )
    }

    /** True when the recent healthy readings are drifting toward the Warning boundary. */
    fun driftDetected(recentHealthyScores: List<Double>, t1: Double, driftRatio: Double): Boolean =
        recentHealthyScores.isNotEmpty() && recentHealthyScores.average() > driftRatio * t1

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
