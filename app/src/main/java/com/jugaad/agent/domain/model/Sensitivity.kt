package com.jugaad.agent.domain.model

/**
 * Per-asset scale factor applied consistently everywhere a threshold, floor or
 * calibration multiplier is computed, so a small/light object can flag small
 * vibration changes without four unrelated magic numbers. See the v16 plan.
 *
 * [factor] is the single source of truth: every consumer (default thresholds,
 * AnomalyScorer's spreadFloor/zFloor, CalibrationMath's k1/k2/MAD floor)
 * multiplies its own base constant by this factor rather than hard-coding a
 * profile-specific number.
 */
enum class Sensitivity(val factor: Double) {
    STANDARD(1.0),
    HIGH(0.5),
    VERY_HIGH(0.25);

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
