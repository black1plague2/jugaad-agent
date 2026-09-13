package com.jugaad.agent.p2p

import kotlinx.coroutines.flow.MutableStateFlow

/** This node's role within the always-on mesh service (v13 plan §7). CLIENT holds no server
 * socket, locks or advertisement; OWNER is exactly the pre-v13 serving behaviour. */
enum class MeshMode { CLIENT, OWNER }

/** Lets UI observe FL sync activity without holding a reference to the service/worker. */
object SyncBus {
    val last = MutableStateFlow<SyncResult?>(null)
    /** True only while the OWNER server socket is actually bound (unchanged v13 semantics). */
    val serving = MutableStateFlow(false)
    /** One line from [AutoJoin] for the Devices tab (waiting / joining / joined / off). */
    val autoJoin = MutableStateFlow<String?>(null)
    /** Process-wide mesh mode (v13 plan §7): switched in-process by [FlSyncService], [Failover]
     * and UI actions so a background caller never needs to call `startForegroundService`. */
    val mode = MutableStateFlow(MeshMode.CLIENT)
    /** Whether [FlSyncService] is currently up (in either mode), independent of [serving]. */
    val serviceRunning = MutableStateFlow(false)
}
