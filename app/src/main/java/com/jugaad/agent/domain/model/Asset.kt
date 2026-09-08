package com.jugaad.agent.domain.model

/** A monitored piece of rotating equipment. Stored at filesDir/assets/<id>/asset.json. */
data class Asset(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    /** Relative path (within the asset folder) of the nameplate photo, if captured. */
    val nameplatePhoto: String? = null,
    val hasBaseline: Boolean = false,
    val thresholds: com.jugaad.agent.ml.anomaly.Thresholds =
        com.jugaad.agent.ml.anomaly.Thresholds.DEFAULT,
)
