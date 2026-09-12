package com.jugaad.agent.fl

import com.jugaad.agent.core.config.AppConfig

/**
 * Shared FedAvg accept-guard rule (v4 plan §1/§4): a merge that would tank held-out
 * accuracy on a variant this node actually trained on is rejected rather than applied.
 * A variant with too little local training data ([AppConfig.AcceptGuard.minTrain]) has
 * nothing worth protecting yet, so any merge is accepted. Both `FedAvgCoordinator`'s
 * client and owner paths call this so the rule can't drift between the two sides.
 */
object AcceptGuard {
    fun accept(nTrain: Int, before: Float, after: Float, cfg: AppConfig): Boolean {
        val guard = cfg.acceptGuard
        return nTrain < guard.minTrain || after >= before - guard.maxDrop.toFloat()
    }
}
