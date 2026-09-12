package com.jugaad.agent.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import com.jugaad.agent.core.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper around [WifiP2pManager] for the FL peer-sync feature.
 *
 * Runtime permission (NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION) is requested by the
 * UI before any method here is called; lint's MissingPermission check is already
 * disabled project-wide (see app/build.gradle.kts), so this class is annotated too
 * for clarity at the call sites.
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    data class Peer(val name: String, val address: String, val status: Int)
    data class GroupInfo(val formed: Boolean, val isGroupOwner: Boolean, val ownerAddress: String?)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val _group = MutableStateFlow(GroupInfo(formed = false, isGroupOwner = false, ownerAddress = null))
    val group: StateFlow<GroupInfo> = _group

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val mgr = manager
        if (mgr == null) {
            Logx.w("WifiDirectManager: WifiP2pManager unavailable on this device")
            return
        }
        if (channel == null) {
            channel = mgr.initialize(context, context.mainLooper, null)
        }
        if (receiver == null) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            val r = P2pReceiver()
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        // A group formed before this process started never re-broadcasts, so ask explicitly.
        channel?.let { ch ->
            mgr.requestConnectionInfo(ch) { info ->
                _group.value = GroupInfo(
                    formed = info.groupFormed,
                    isGroupOwner = info.isGroupOwner,
                    ownerAddress = info.groupOwnerAddress?.hostAddress,
                )
            }
            mgr.requestPeers(ch) { deviceList ->
                _peers.value = deviceList.deviceList.map {
                    Peer(name = it.deviceName, address = it.deviceAddress, status = it.status)
                }
            }
        }
    }

    fun stop() {
        val r = receiver ?: return
        try {
            context.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
            Logx.w("WifiDirectManager: receiver already unregistered", e)
        }
        receiver = null
    }

    fun discover() {
        val ch = channel ?: return
        manager?.discoverPeers(ch, actionListener("discoverPeers"))
    }

    fun connect(address: String) {
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        manager?.connect(ch, config, actionListener("connect"))
    }

    fun createGroup() {
        val ch = channel ?: return
        manager?.createGroup(ch, actionListener("createGroup"))
    }

    fun removeGroup() {
        val ch = channel ?: return
        manager?.removeGroup(ch, actionListener("removeGroup"))
    }

    private fun actionListener(op: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Logx.d("WifiDirectManager: $op succeeded")
        }

        override fun onFailure(reason: Int) {
            Logx.w("WifiDirectManager: $op failed (${reasonToString(reason)})")
        }
    }

    private fun reasonToString(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "UNKNOWN($reason)"
    }

    private inner class P2pReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val mgr = manager
            val ch = channel
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Logx.d("WifiDirectManager: p2p state changed -> $state")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (mgr != null && ch != null) {
                        mgr.requestPeers(ch) { deviceList ->
                            _peers.value = deviceList.deviceList.map {
                                Peer(name = it.deviceName, address = it.deviceAddress, status = it.status)
                            }
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (mgr != null && ch != null) {
                        mgr.requestConnectionInfo(ch) { info ->
                            _group.value = GroupInfo(
                                formed = info.groupFormed,
                                isGroupOwner = info.isGroupOwner,
                                ownerAddress = info.groupOwnerAddress?.hostAddress,
                            )
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Logx.d("WifiDirectManager: this device changed")
                }
            }
        }
    }
}
