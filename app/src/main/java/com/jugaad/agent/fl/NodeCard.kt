package com.jugaad.agent.fl

import kotlinx.serialization.Serializable

/** One node's snapshot, as broadcast in [NetworkState.nodes] and the HELLO header. */
@Serializable
data class NodeCard(
    val deviceId: String,
    val name: String,
    val mode: NodeMode,
    val challenger: String?,
    val isOwner: Boolean,
    val champRound: Int,
    val champValAcc: Float,
    val challValAcc: Float,
    val nTrain: Int,
    val nVal: Int,
    val lastSeenMs: Long,
)
