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
}
