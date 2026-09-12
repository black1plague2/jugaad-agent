package com.jugaad.agent.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.jugaad.agent.core.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.util.ArrayDeque

/**
 * Peer discovery on the local WiFi network via mDNS/DNS-SD ([NsdManager]), next to WiFi Direct.
 * While [FlSyncService] serves, the owner advertises `_jugaad-fl._tcp.` under its node name;
 * clients list advertised owners by node name and sync to the owner's LAN address directly,
 * with no pairing dialog. WiFi Direct stays the path when there is no router.
 */
class LanDiscovery(context: Context) {
    data class LanPeer(val name: String, val deviceId: String, val host: String, val port: Int) {
        /** Android's WiFi Direct group subnet; only reachable by members of that group. */
        val isP2pAddress: Boolean get() = host.startsWith("192.168.49.")
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val lock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun advertise(name: String, deviceId: String, port: Int) {
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Logx.i("lan: advertising '${i.serviceName}' on port $port")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Logx.w("lan: advertise failed code=$code")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Logx.w("lan: registerService threw", it); registrationListener = null }
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Logx.d("lan: discovery started")
            }
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) enqueue(i)
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == i.serviceName }
                Logx.i("lan: lost '${i.serviceName}'")
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Logx.w("lan: discovery start failed code=$code")
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Logx.w("lan: discoverServices threw", it); discoveryListener = null }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        synchronized(lock) { resolveQueue.clear(); resolving = false }
        _peers.value = emptyList()
    }

    /** Resolves one service at a time: NsdManager rejects overlapping resolves. */
    private fun enqueue(info: NsdServiceInfo) {
        synchronized(lock) { resolveQueue.add(info) }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            if (resolving) return
            val n = resolveQueue.poll() ?: return
            resolving = true
            n
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                Logx.w("lan: resolve '${i.serviceName}' failed code=$code")
                synchronized(lock) { resolving = false }
                resolveNext()
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val addr = i.host
                val id = i.attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: ""
                if (addr is Inet4Address) {
                    val peer = LanPeer(i.serviceName, id, addr.hostAddress ?: "", i.port)
                    // The owner advertises on every interface; when the same name resolves a
                    // second time, keep a router address over its WiFi Direct group address
                    // (192.168.49.x), which a LAN-only client cannot reach.
                    val existing = _peers.value.firstOrNull { it.name == peer.name }
                    if (existing == null || !peer.isP2pAddress || existing.isP2pAddress) {
                        _peers.value = _peers.value.filterNot { it.name == peer.name } + peer
                        Logx.i("lan: found '${peer.name}' id=${peer.deviceId} at ${peer.host}:${peer.port}")
                    }
                } else {
                    Logx.w("lan: '${i.serviceName}' resolved to non-IPv4 $addr, ignored")
                }
                synchronized(lock) { resolving = false }
                resolveNext()
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_jugaad-fl._tcp."

        /** Which advertised owner a client syncs to when nobody picked one by hand: the last
         * owner if it is still visible, else the only one visible, else none (the user chooses). */
        fun pickOwner(peers: List<LanPeer>, lastOwnerAddress: String?): LanPeer? =
            peers.firstOrNull { it.host == lastOwnerAddress } ?: peers.singleOrNull()
    }
}
