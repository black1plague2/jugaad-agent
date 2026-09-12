package com.jugaad.agent.fl

import com.jugaad.agent.core.config.ConfigStore

/**
 * Pure network-scoring rules run by the owner after a sync window (Network
 * protocol v2). No I/O, no TFLite — exercised directly by [PromotionTest]. Margin,
 * wins-to-promote, eligibility floor and history length come from
 * [com.jugaad.agent.core.config.Promotion] (`AppConfig.promotion`), read from
 * [ConfigStore.effective] at call time (v4 plan §1) — not to be confused with this
 * object's own name.
 */
object Promotion {

    /** One node's report for one variant this sync window. */
    data class Report(val deviceId: String, val variantId: String, val nVal: Int, val valAcc: Float, val trainMs: Long = 0L)

    /** Event log cap (v4 §7, "hardware and scale, already in place, keep honest") — not a config key. */
    private const val EVENTS_CAP = 50

    /** [trainMs] is the nVal-weighted mean of [Report.trainMs] over the same reports [netAcc] is weighted from — [Report] has no nTrain of its own to weight by. */
    private data class Computed(val netAcc: Float, val nVal: Int, val nodes: Int, val eligible: Boolean, val trainMs: Float)

    /**
     * Recomputes standings for this round from [reports] (already `valAcc >= 0`
     * reports are weighted by `nVal`), advances win streaks against the current
     * champion, and promotes + resets all streaks on the 2nd consecutive win.
     */
    fun update(state: NetworkState, reports: List<Report>, roundByVariant: Map<String, Int>, nowMs: Long): NetworkState {
        val promoCfg = ConfigStore.effective.value.promotion
        val minTotalNVal = promoCfg.minNVal
        val promoteMargin = promoCfg.margin.toFloat()
        val winsToPromote = promoCfg.winsRequired
        val historySize = promoCfg.historyLen

        val byVariant = reports.filter { it.valAcc >= 0f && it.nVal > 0 }.groupBy { it.variantId }
        val variantIds = (state.standings.keys + byVariant.keys + roundByVariant.keys + setOf(state.championId)).toSortedSet()

        val computed = LinkedHashMap<String, Computed>()
        for (vid in variantIds) {
            val vReports = byVariant[vid].orEmpty()
            val totalNVal = vReports.sumOf { it.nVal }
            val netAcc = if (totalNVal > 0) {
                (vReports.sumOf { (it.valAcc * it.nVal).toDouble() } / totalNVal).toFloat()
            } else {
                -1f
            }
            val trainMs = if (totalNVal > 0) {
                (vReports.sumOf { it.trainMs.toDouble() * it.nVal } / totalNVal).toFloat()
            } else {
                0f
            }
            computed[vid] = Computed(
                netAcc = netAcc,
                nVal = totalNVal,
                nodes = vReports.map { it.deviceId }.distinct().size,
                eligible = totalNVal >= minTotalNVal,
                trainMs = trainMs,
            )
        }

        val championId = state.championId
        val champ = computed[championId]

        // Wins: consecutive eligible rounds beating the champion by the promotion margin.
        // A non-eligible round breaks the streak. First variant to reach 2 wins is promoted.
        var promoted: String? = null
        val newWins = LinkedHashMap<String, Int>()
        for (vid in variantIds) {
            if (vid == championId) continue
            val c = computed[vid]!!
            val prevWins = state.standings[vid]?.wins ?: 0
            val beatsChampion = champ != null && c.eligible && champ.eligible && c.netAcc >= champ.netAcc + promoteMargin
            val wins = if (beatsChampion) prevWins + 1 else 0
            newWins[vid] = wins
            if (promoted == null && wins >= winsToPromote) promoted = vid
        }
        if (promoted != null) {
            for (vid in newWins.keys.toList()) newWins[vid] = 0
        }
        val finalChampionId = promoted ?: championId

        val finalChampAcc = computed[finalChampionId]?.netAcc ?: -1f
        val newStandings = LinkedHashMap<String, VariantStanding>()
        for (vid in variantIds) {
            val c = computed[vid]!!
            val prev = state.standings[vid]
            val round = roundByVariant[vid] ?: prev?.round ?: 0
            val history = (prev?.history.orEmpty() + c.netAcc).takeLast(historySize)
            val wins = if (vid == finalChampionId) 0 else (newWins[vid] ?: 0)
            val usesUnlabelled = runCatching { FlVariants.byId(vid).usesUnlabelled }.getOrDefault(false)
            // The champion scores 0 by definition (Decision 7) rather than whatever the
            // formula happens to produce against itself (it would be negative, from its
            // own training-cost term).
            val score = if (vid == finalChampionId) 0f else StrategyRank.score(c.netAcc, finalChampAcc, c.trainMs, usesUnlabelled)
            newStandings[vid] = VariantStanding(vid, c.netAcc, c.nVal, c.nodes, history, wins, round, c.trainMs, score, usesUnlabelled)
        }

        val events = state.events.toMutableList()
        if (promoted != null) {
            events += NetworkEvent(
                ts = nowMs,
                type = EventType.PROMOTE,
                text = "$promoted promoted to champion (netAcc=${"%.3f".format(computed[promoted]!!.netAcc)}, was $championId)",
            )
        }

        return state.copy(
            championId = finalChampionId,
            standings = newStandings,
            events = events.takeLast(EVENTS_CAP),
            updatedMs = nowMs,
        )
    }

    /** Assigns [node] the least-populated non-champion challenger, if it needs one. */
    fun assign(state: NetworkState, node: NodeCard): NetworkState {
        if (node.mode != NodeMode.EXPERIMENTAL) return state
        if (node.challenger != null) return state
        if (state.assignments.containsKey(node.deviceId)) return state

        val challengerIds = FlVariants.challengers.map { it.id }.filterNot { it == state.championId }
        if (challengerIds.isEmpty()) return state

        val counts = challengerIds.associateWithTo(HashMap<String, Int>()) { 0 }
        for (v in state.assignments.values) counts[v] = (counts[v] ?: 0) + 1
        val chosen = challengerIds.minByOrNull { counts[it] ?: 0 } ?: return state

        val event = NetworkEvent(
            ts = System.currentTimeMillis(),
            type = EventType.ASSIGN,
            text = "${node.name} assigned challenger $chosen",
        )
        return state.copy(
            assignments = state.assignments + (node.deviceId to chosen),
            events = (state.events + event).takeLast(EVENTS_CAP),
        )
    }
}
