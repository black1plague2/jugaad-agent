package com.jugaad.agent.fl

import android.content.Context
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.p2p.SyncNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Kept as a literal by design (v4 plan §1: "debounce stays 8 s unless you add a key"). */
private const val DEBOUNCE_MS = 8_000L
private const val UNCERTAIN_VARIANT_ID = "uncertain"

/**
 * Reacts to newly labelled samples ([SampleStore.revision]) and newly arrived peer
 * samples ([FlRuntime.dataRevision]) by retraining this node's held variants, debounced
 * so a burst of captures/syncs triggers one training pass rather than one per event.
 * Honours [TrainBudget.allowAutoTrain] (thermal/battery), and only fires the
 * `uncertain` variant when the newest sample looks genuinely uncertain to the champion
 * — the confidence trigger comes from [com.jugaad.agent.core.config.Recipes.uncertainTrigger]
 * (Decisions 5/8, v3; v4 plan §1).
 */
class AutoTrainer(
    private val runtime: FlRuntime,
    private val scope: CoroutineScope,
    private val context: Context,
) {

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            combine(runtime.store.revision, runtime.dataRevision) { s, d -> s to d }
                .drop(1) // the initial value on subscribe isn't a "new sample" event
                .debounce(DEBOUNCE_MS)
                .collectLatest {
                    if (!runtime.config.value.autoTrain) return@collectLatest

                    val budget = TrainBudget.snapshot(context)
                    if (!budget.allowAutoTrain) {
                        Logx.i("AutoTrainer: skipped - ${budget.reason}")
                        return@collectLatest
                    }

                    val idsToTrain = runtime.heldVariantIds()
                        .filter { id -> id != UNCERTAIN_VARIANT_ID || uncertainShouldFire() }
                    if (idsToTrain.isEmpty()) return@collectLatest

                    runCatching { idsToTrain.map { runtime.trainer(it).train() } }
                        .onSuccess {
                            runtime.addEvent(EventType.TRAIN, "auto-train: ${idsToTrain.joinToString()}")
                            if (runtime.config.value.autoSync) {
                                // Goes through SyncNow.asClient, same as the manual buttons and the
                                // scheduler, so a failed auto-sync counts toward failover too (v4 §4).
                                runCatching { SyncNow.asClient(context) }
                                    .onFailure { t -> Logx.w("AutoTrainer: auto-sync failed", t) }
                            }
                        }
                        .onFailure { t -> Logx.w("AutoTrainer: train failed", t) }
                }
        }
    }

    /**
     * Newest sample's champion max prob < `recipes.uncertainTrigger` (founder table row 3,
     * uncertainty-triggered fine-tuning). [SampleStore] doesn't expose the newest PENDING sample — only
     * [SampleStore.labelled] is a listable API (the same gap [VariantTrainer]'s `distill`
     * recipe hits) — so this uses the newest labelled sample as the best available proxy.
     */
    private fun uncertainShouldFire(): Boolean {
        val newest = (runtime.store.labelled() + runtime.store.pending()).maxByOrNull { it.ts } ?: return false
        val champProbs = runtime.trainer(runtime.championId.value).infer(newest.x)
        val trigger = ConfigStore.effective.value.recipes.uncertainTrigger.toFloat()
        return (champProbs.maxOrNull() ?: 1f) < trigger
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
