package com.jugaad.agent.fl

/**
 * Pure early-stopping decision rule for [VariantTrainer.train] (Decision 4, v3): after
 * each epoch's held-out loss comes in, keep the lowest-loss epoch seen so far and stop
 * once [PATIENCE] epochs pass with no improvement. Kept free of [FlModel]/TFLite so the
 * selection logic is directly unit-testable against a fixed sequence of epoch losses,
 * without a fake trainer needing to run any real epochs.
 */
object EarlyStopping {
    const val PATIENCE = 5

    /** `bestEpoch`/`bestLoss` seen so far; `noImprovement` counts epochs since the last new best. */
    data class State(val bestEpoch: Int, val bestLoss: Float, val noImprovement: Int)

    /** The epoch with the lowest loss, and the epoch count [VariantTrainer.train] actually ran. */
    data class Result(val bestEpoch: Int, val stopEpoch: Int)

    fun initial(): State = State(bestEpoch = -1, bestLoss = Float.MAX_VALUE, noImprovement = 0)

    /**
     * One epoch's decision. @return the updated state and whether the caller should stop
     * training after this epoch (patience exhausted).
     */
    fun step(state: State, epoch: Int, valLoss: Float, patience: Int = PATIENCE): Pair<State, Boolean> =
        if (valLoss < state.bestLoss) {
            state.copy(bestEpoch = epoch, bestLoss = valLoss, noImprovement = 0) to false
        } else {
            val noImprovement = state.noImprovement + 1
            state.copy(noImprovement = noImprovement) to (noImprovement >= patience)
        }

    /**
     * Test/simulation convenience: replays a known sequence of per-epoch held-out losses
     * through [step] and returns what an online caller following [step] would have
     * decided — the best epoch, and the epoch count training would stop at.
     */
    fun run(valLosses: List<Float>, patience: Int = PATIENCE): Result {
        var state = initial()
        for (epoch in valLosses.indices) {
            val (next, stop) = step(state, epoch, valLosses[epoch], patience)
            state = next
            if (stop) return Result(state.bestEpoch, epoch + 1)
        }
        return Result(state.bestEpoch, valLosses.size)
    }
}
