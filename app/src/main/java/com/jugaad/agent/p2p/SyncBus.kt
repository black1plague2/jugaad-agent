package com.jugaad.agent.p2p

import kotlinx.coroutines.flow.MutableStateFlow

/** Lets UI observe FL sync activity without holding a reference to the service/worker. */
object SyncBus {
    val last = MutableStateFlow<SyncResult?>(null)
    val serving = MutableStateFlow(false)
}
