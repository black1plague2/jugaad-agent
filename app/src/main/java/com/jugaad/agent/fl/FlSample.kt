package com.jugaad.agent.fl

/** How a sample got its label: a technician tap, our own auto-healthy heuristic, none yet, or a peer over sync. */
enum class SampleSource { HUMAN, AUTO, PENDING, PEER }

/** One captured reading queued for (or already used in) local training. */
data class FlSample(
    val id: String,
    val assetId: String,
    val x: FloatArray,
    val label: Int?,
    val source: SampleSource,
    val ts: Long,
    /** Anomaly score at capture time, used by [com.jugaad.agent.domain.usecase.CalibrateUseCase]. */
    val score: Double? = null,
    /** 256-d absolute log-mel feature at capture time, used by [com.jugaad.agent.domain.usecase.RefreshBaselineUseCase]. */
    val abs: FloatArray? = null,
    /** 4 absolute sensor indices (accel, gyro, mag, magRms) at capture time. */
    val absSensors: FloatArray? = null,
    /** Where this sample came from: null for locally-captured, else the contributing device's origin id. */
    val origin: String? = null,
    /** The asset's machine-type catalogue id at capture time, if known. */
    val machineTypeId: String? = null,
)
