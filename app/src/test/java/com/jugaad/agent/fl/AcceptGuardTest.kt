package com.jugaad.agent.fl

import com.jugaad.agent.core.config.AcceptGuard as AcceptGuardConfig
import com.jugaad.agent.core.config.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared FedAvg accept-guard rule (v4 plan §1/§4): [AcceptGuard.accept] reads its thresholds from [AppConfig.acceptGuard]. */
class AcceptGuardTest {

    private val defaultCfg = AppConfig() // minTrain = 5, maxDrop = 0.10

    @Test
    fun acceptsAnyMergeWhenTooLittleLocalTrainingDataToProtect() {
        assertTrue(AcceptGuard.accept(nTrain = 4, before = 0.90f, after = 0.10f, cfg = defaultCfg))
    }

    @Test
    fun acceptsWhenAccuracyDropIsWithinTheGuard() {
        assertTrue(AcceptGuard.accept(nTrain = 10, before = 0.80f, after = 0.75f, cfg = defaultCfg)) // drop 0.05 <= 0.10
    }

    @Test
    fun acceptsWhenTheMergeImprovesAccuracy() {
        assertTrue(AcceptGuard.accept(nTrain = 10, before = 0.70f, after = 0.85f, cfg = defaultCfg))
    }

    @Test
    fun rejectsWhenAccuracyDropExceedsTheGuard() {
        assertFalse(AcceptGuard.accept(nTrain = 10, before = 0.80f, after = 0.60f, cfg = defaultCfg)) // drop 0.20 > 0.10
    }

    @Test
    fun rejectsExactlyAtTheMinTrainBoundary() {
        // nTrain == minTrain (5) -> no longer "too little data", the drop guard applies.
        assertFalse(AcceptGuard.accept(nTrain = 5, before = 0.80f, after = 0.60f, cfg = defaultCfg))
    }

    @Test
    fun honoursACustomMaxDropFromConfig() {
        val loose = AppConfig(acceptGuard = AcceptGuardConfig(minTrain = 5, maxDrop = 0.30))
        assertTrue(AcceptGuard.accept(nTrain = 10, before = 0.80f, after = 0.60f, cfg = loose)) // drop 0.20 <= 0.30
    }

    // D3: with no held-out data, evaluateVal returns the -1 sentinel for both `before` and
    // `after`. The naive `-1f >= -1f - maxDrop` reads as true by accident; the guard must
    // recognise the sentinel explicitly rather than let that accident decide the outcome.
    @Test
    fun sentinelBeforeAndAfterIsTreatedAsNoEvidenceNotAsAnAccuracyHold() {
        assertTrue(AcceptGuard.accept(nTrain = 10, before = -1f, after = -1f, cfg = defaultCfg))
    }

    @Test
    fun sentinelOnOnlyOneSideIsAlsoTreatedAsNoEvidence() {
        assertTrue(AcceptGuard.accept(nTrain = 10, before = -1f, after = 0.5f, cfg = defaultCfg))
        assertTrue(AcceptGuard.accept(nTrain = 10, before = 0.5f, after = -1f, cfg = defaultCfg))
    }

    @Test
    fun nonFiniteAccuracyIsAlsoTreatedAsNoEvidence() {
        assertTrue(AcceptGuard.accept(nTrain = 10, before = Float.NaN, after = 0.5f, cfg = defaultCfg))
        assertTrue(AcceptGuard.accept(nTrain = 10, before = 0.5f, after = Float.POSITIVE_INFINITY, cfg = defaultCfg))
    }

    // Ordinary two-real-accuracies behaviour must be unchanged by the sentinel check.
    @Test
    fun ordinaryTwoRealAccuraciesBehaviourIsUnchanged() {
        assertTrue(AcceptGuard.accept(nTrain = 10, before = 0.80f, after = 0.75f, cfg = defaultCfg))
        assertFalse(AcceptGuard.accept(nTrain = 10, before = 0.80f, after = 0.60f, cfg = defaultCfg))
    }
}
