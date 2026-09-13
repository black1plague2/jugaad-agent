package com.jugaad.agent.domain.model

/**
 * Per-asset scale factor applied consistently everywhere a *threshold* is computed,
 * so a small/light object can flag small vibration changes without unrelated magic
 * numbers. See the v16 plan.
 *
 * [factor] is the single source of truth: every consumer (default t1/t2 thresholds,
 * CalibrationMath's k1/k2/MAD floor) multiplies its own base constant by this factor
 * rather than hard-coding a profile-specific number. It does NOT scale the score
 * itself — AnomalyScorer's spreadFloor and zFloor stay at their global, sensitivity-
 * independent values, so the same cosine distance and sensor deltas always produce
 * the same score; only the thresholds that bucket that score into Healthy/Warning/
 * Critical move. Scaling the floors as well double-applies sensitivity (thresholds
 * fall AND the score denominator shrinks), which turns ordinary noise into a false
 * critical reading.
 *
 * Why 0.75 and 0.5, not 0.5 and 0.25: measured on phone A (2026-09-13), five readings
 * of a phone lying still on a desk scored 0.90, 1.12, 1.12, 1.97 and 6.36 (the last a
 * knock). Resting measurement noise is therefore about 0.9 to 2.0, and no threshold
 * can separate a real change smaller than that. The old factors 0.5 and 0.25 put the
 * critical threshold (4.0 * f) at 2.0 and 1.0, below resting noise, so a still object
 * read Critical four times in five. Factors are chosen so the critical threshold stays
 * above resting noise (High 3.0, Very high 2.0) while the healthy band shrinks into
 * warnings: at rest High gives one warning in four and Very high warns more, which the
 * Very high description already tells the user. Sensitivity scales thresholds and
 * calibration only, never the score's noise floors.
 */
enum class Sensitivity(val factor: Double) {
    STANDARD(1.0),
    HIGH(0.75),
    VERY_HIGH(0.5);

    /** One line describing what this profile does, shown next to the selector. */
    val description: String
        get() = when (this) {
            STANDARD -> "Most machines. Flags clear changes."
            HIGH -> "Small or light machines. Flags smaller changes."
            VERY_HIGH -> "Very small or light objects such as a box. Flags the smallest changes, and handling can trigger warnings, so place it steadily."
        }

    val label: String
        get() = when (this) {
            STANDARD -> "Standard"
            HIGH -> "High"
            VERY_HIGH -> "Very high"
        }

    companion object {
        val DEFAULT = STANDARD

        /** Never throws — an unknown/missing name (old data, corrupt config) falls back to [DEFAULT]. */
        fun fromName(raw: String?): Sensitivity =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
