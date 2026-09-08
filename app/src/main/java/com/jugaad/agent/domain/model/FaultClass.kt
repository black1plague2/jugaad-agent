package com.jugaad.agent.domain.model

/** Output classes of the small CNN. Index order MUST match training (ml/train_cnn.py). */
enum class FaultClass(val index: Int, val label: String) {
    HEALTHY(0, "Healthy"),
    ROTOR_IMBALANCE(1, "Rotor Imbalance"),
    AIRFLOW_OBSTRUCTION(2, "Airflow Obstruction");

    companion object {
        fun fromIndex(i: Int): FaultClass = entries.firstOrNull { it.index == i } ?: HEALTHY
    }
}
