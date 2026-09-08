package com.jugaad.agent.domain.model

/** Traffic-light health state shown as the big status pill. */
enum class MachineStatus {
    HEALTHY,
    WARNING,
    CRITICAL;

    val label: String
        get() = when (this) {
            HEALTHY -> "Healthy"
            WARNING -> "Warning"
            CRITICAL -> "Critical"
        }

    companion object {
        fun fromScore(score: Double, t1: Double, t2: Double): MachineStatus = when {
            score <= t1 -> HEALTHY
            score <= t2 -> WARNING
            else -> CRITICAL
        }
    }
}
