package com.jugaad.agent.ml.executorch

import android.os.Build
import com.jugaad.agent.core.Logx

/**
 * Best-effort System-on-Chip identification, used to decide whether to try the
 * QNN (Hexagon NPU) .pte or fall back to the XNNPACK (CPU) .pte.
 *
 * Primary target: iQOO 15 — Snapdragon 8 Elite Gen 5, part number **SM8850**.
 * Secondary target: Redmi Note 10 Pro — Snapdragon 732G (SM7150), CPU path only.
 */
object SocDetector {

    data class SocInfo(
        val socModel: String,
        val socManufacturer: String,
        val isQualcomm: Boolean,
        /** True when we believe the Hexagon HTP / NPU is usable for QNN delegation. */
        val htpCapable: Boolean,
        val displayName: String,
    )

    /** Known Qualcomm parts with a strong Hexagon HTP (add rows as needed). */
    private val HTP_PARTS = mapOf(
        "SM8850" to "Snapdragon 8 Elite Gen 5",
        "SM8750" to "Snapdragon 8 Elite",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
    )

    val info: SocInfo by lazy { detect() }

    private fun detect(): SocInfo {
        val model: String
        val maker: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            model = Build.SOC_MODEL.uppercase()
            maker = Build.SOC_MANUFACTURER
        } else {
            model = (Build.BOARD ?: "").uppercase()
            maker = Build.HARDWARE ?: ""
        }
        val hw = (Build.HARDWARE ?: "").lowercase()
        val isQc = maker.contains("qualcomm", true) ||
            hw.startsWith("qcom") || model.startsWith("SM") || model.startsWith("QCS")

        val known = HTP_PARTS.entries.firstOrNull { model.contains(it.key) }
        val htp = isQc && known != null

        val name = known?.value
            ?: if (isQc) "Qualcomm $model" else "$maker $model".trim()

        Logx.i("SoC: model=$model maker=$maker qualcomm=$isQc htpCapable=$htp ($name)")
        return SocInfo(model, maker, isQc, htp, name)
    }
}
