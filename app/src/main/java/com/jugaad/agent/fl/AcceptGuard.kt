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
        if (nTrain < guard.minTrain) return true
        // `before`/`after` are -1 (the "no held-out data" sentinel from evaluateVal, e.g. when
        // nVal < training.minVal) or otherwise non-finite when there is no real accuracy to
        // compare. `-1f >= -1f - maxDrop` is true by accident (D3): a sentinel vs. sentinel
        // comparison must not silently read as "accuracy held up". With no evidence either way,
        // and the founder mid-collection of new classes, we choose to accept rather than stall
        // every merge until held-out data exists — mirroring the nTrain-too-low branch above,
        // where there's nothing measured yet worth protecting.
        if (before < 0f || after < 0f || !before.isFinite() || !after.isFinite()) return true
        return after >= before - guard.maxDrop.toFloat()
    }
}
