package com.jugaad.agent.fl

import com.jugaad.agent.core.config.ConfigStore

/**
 * Strategy ranking (Decision 7, v3): scores every variant against the champion on
 * accuracy gained, training cost, and whether it can improve without technician
 * confirmations at all (`usesUnlabelled`). This is a superset of [Promotion]'s
 * champion/challenger contest — a network of one node still gets a usable leaderboard.
 * [Promotion.update] computes and stores each [VariantStanding.score] using [score];
 * this object's [rank] just reads them back out, sorted. The accuracy weight is fixed
 * (100 points per unit of accuracy — the scale everything else is denominated in); the
 * cost weight and unlabelled bonus come from [com.jugaad.agent.core.config.Ranking]
 * (`AppConfig.ranking`), read from [ConfigStore.effective] at call time (v4 plan §1).
 */
object StrategyRank {
    private const val ACC_WEIGHT = 100f

    /**
     * `100 * (netAcc - champAcc) - costPerMs * trainMs + unlabelledBonus * usesUnlabelled`.
     * The champion's own score is 0 by definition — callers should not run the champion
     * through this, see [Promotion.update].
     */
    fun score(netAcc: Float, champAcc: Float, trainMs: Float, usesUnlabelled: Boolean): Float {
        val ranking = ConfigStore.effective.value.ranking
        return ACC_WEIGHT * (netAcc - champAcc) - ranking.costPerMs.toFloat() * trainMs +
            (if (usesUnlabelled) ranking.unlabelledBonus.toFloat() else 0f)
    }

    /** [NetworkState.standings] sorted desc by [VariantStanding.score]; the champion is included at 0. */
    fun rank(state: NetworkState): List<VariantStanding> = state.standings.values.sortedByDescending { it.score }
}
