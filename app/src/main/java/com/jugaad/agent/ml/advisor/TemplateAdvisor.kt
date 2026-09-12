package com.jugaad.agent.ml.advisor

import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.domain.model.FaultClass
import com.jugaad.agent.domain.model.MachineStatus

/**
 * Deterministic advice generator. Always available, no model, &lt;1 ms.
 * Used when Gemma is disabled or fails to load. Phrasing is intentionally close to
 * what the LLM prompt asks for so the demo reads the same either way.
 */
class TemplateAdvisor : MaintenanceAdvisor {

    override val source = Diagnosis.AdviceSource.TEMPLATE
    override val isReady = true

    override suspend fun advise(ctx: AdviceContext): String {
        val topIssue = ctx.topIssue
        if (topIssue != null) {
            val label = topIssue.label.replaceFirstChar { it.lowercaseChar() }
            val pct = (topIssue.confidence * 100).toInt()
            return "Likely $label ($pct%). ${topIssue.action}"
        }
        val freq = if (ctx.dominantHz > 0) "around ${ctx.dominantHz.toInt()} Hz" else "across the spectrum"
        return when (ctx.status) {
            MachineStatus.HEALTHY ->
                "The machine sounds normal and vibration is within the healthy range. Keep to the regular maintenance schedule."

            MachineStatus.WARNING -> when (ctx.fault) {
                FaultClass.ROTOR_IMBALANCE ->
                    "Early signs of rotor imbalance: extra vibration $freq. Check the fan or rotor for dirt build-up or a loose blade, and re-check in a day."
                FaultClass.AIRFLOW_OBSTRUCTION ->
                    "Airflow may be partly blocked, adding noise $freq. Inspect and clean the filter, inlet and ducting, then re-measure."
                else ->
                    "Something has changed since the healthy baseline (extra energy $freq). Do a quick visual and touch check for looseness or blockage, and re-measure soon."
            }

            MachineStatus.CRITICAL -> when (ctx.fault) {
                FaultClass.ROTOR_IMBALANCE ->
                    "Strong rotor imbalance $freq. Stop the machine when safe, secure or clean the rotor/fan, and do not run it under load until the vibration drops."
                FaultClass.AIRFLOW_OBSTRUCTION ->
                    "Severe airflow restriction $freq, which can overheat the motor. Shut down, clear the blockage and clean all filters before restarting."
                else ->
                    "The machine is far outside its healthy baseline (high energy $freq). Stop it as soon as it is safe and get a technician to inspect the rotating parts before further use."
            }
        }
    }
}
