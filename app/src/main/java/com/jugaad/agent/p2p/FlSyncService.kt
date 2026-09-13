package com.jugaad.agent.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jugaad.agent.R
import com.jugaad.agent.core.Logx
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.fl.FlRuntime
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.ui.services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ServerSocket

/**
 * Foreground service that runs while this phone is the WiFi Direct group owner: listens
 * on [SyncProtocol.port] and merges whatever clients show up in each accept window.
 */
class FlSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var loopJob: Job? = null
    private var wifiManager: WifiDirectManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastStepDownCheckMs = 0L
    private var pendingStepDownId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val flRuntime = applicationContext.services().flRuntime.value
        if (flRuntime == null) {
            Logx.w("FlSyncService: flRuntime not ready yet, stopping")
            stopSelf()
            return START_STICKY
        }

        // Held while this device serves as group owner (screen sleep otherwise stalls the
        // accept loop below until the phone is woken, per the real device test).
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jugaad:fl-sync")
            ?.apply { acquire() }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jugaad:fl-wifi")
            ?.apply { acquire() }

        val manager = WifiDirectManager(applicationContext)
        wifiManager = manager
        manager.start()

        loopJob = scope.launch {
            val socket = bindServerSocket(manager) ?: return@launch
            server = socket
            SyncBus.serving.value = true
            // Advertise on the local WiFi network too, so phones on the same router find this
            // owner by node name without the WiFi Direct pairing dialog (v6 plan).
            flRuntime.config.value.let { c ->
                applicationContext.services().lanDiscovery.advertise(c.name, c.deviceId, socket.localPort)
            }

            val coordinator = FedAvgCoordinator(RuntimePeer(flRuntime))
            val lan = applicationContext.services().lanDiscovery

            while (isActive) {
                val result = coordinator.serveOnce(socket)
                if (result.peers > 0) {
                    SyncBus.last.value = result
                    if (flRuntime.config.value.lastRole != NodeRole.OWNER) {
                        flRuntime.updateConfig { it.copy(lastRole = NodeRole.OWNER) }
                    }
                }
                if (checkStepDown(flRuntime, lan)) break
            }
        }

        return START_STICKY
    }

    /**
     * Waits for [WifiDirectManager.group] to report formed (connection info is already
     * requested as part of [WifiDirectManager.start]); if it never forms, creates one and
     * retries with [com.jugaad.agent.core.config.Sync.retryBackoffMs] before giving up and
     * binding the listening socket anyway (v4 plan §4). Null only if the socket itself
     * can't be opened (port already in use), in which case the service stops.
     */
    private suspend fun bindServerSocket(manager: WifiDirectManager): ServerSocket? {
        val formed = withTimeoutOrNull(2000) { manager.group.first { it.formed } } != null
        if (!formed) {
            manager.createGroup()
            for (backoffMs in ConfigStore.effective.value.sync.retryBackoffMs) {
                if (withTimeoutOrNull(backoffMs.toLong()) { manager.group.first { it.formed } } != null) break
            }
        }

        val port = SyncProtocol.port()
        return try {
            ServerSocket(port).apply { soTimeout = 1000 }
        } catch (e: IOException) {
            Logx.w("FlSyncService: failed to open server socket on port $port", e)
            stopSelf()
            null
        }
    }

    /**
     * Owner-side flap guard around [Failover.shouldStepDown]: only currently advertised owners
     * are candidates (nobody but an owner calls [LanDiscovery.advertise]), and a candidate must
     * show up on two checks [com.jugaad.agent.core.config.Sync.autoJoinIntervalMs] apart before
     * this phone yields to it, so one stray or late mDNS resolve doesn't flip ownership back and
     * forth. Persists CLIENT and the winner's address, then tears down via [stopSelf] (the
     * existing [onDestroy] path: server close, WiFi Direct teardown, unadvertise, locks); the
     * caller breaks its loop immediately after. Returns whether it yielded.
     */
    private fun checkStepDown(flRuntime: FlRuntime, lan: LanDiscovery): Boolean {
        val now = System.currentTimeMillis()
        val intervalMs = ConfigStore.effective.value.sync.autoJoinIntervalMs.toLong()
        if (now - lastStepDownCheckMs < intervalMs) return false
        lastStepDownCheckMs = now

        val myId = flRuntime.config.value.deviceId
        val others = lan.peers.value.filterNot { it.deviceId == myId }
        val candidate = others.filter { it.deviceId < myId }.minByOrNull { it.deviceId }

        if (candidate == null || candidate.deviceId != pendingStepDownId) {
            pendingStepDownId = candidate?.deviceId
            return false
        }
        if (!Failover.shouldStepDown(myId, others.map { it.deviceId })) return false

        Logx.i("owner: yielding to ${candidate.name}, lower id")
        flRuntime.updateConfig { it.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = candidate.host) }
        stopSelf()
        return true
    }

    override fun onDestroy() {
        loopJob?.cancel()
        try {
            server?.close()
        } catch (e: IOException) {
            Logx.w("FlSyncService: error closing server socket", e)
        }
        server = null
        wifiManager?.stop()
        wifiManager = null
        applicationContext.services().lanDiscovery.stopAdvertising()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        scope.cancel()
        SyncBus.serving.value = false
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Syncing federated model over WiFi Direct. Keeps the radio awake so peers can reach this phone.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "fl_sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "fl_sync"
        private const val NOTIFICATION_ID = 4210

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, FlSyncService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FlSyncService::class.java))
        }
    }
}
