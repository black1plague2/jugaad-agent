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
 * Always-on foreground mesh service (v13 plan §7, fixing H6/H7): runs whenever the FL runtime
 * is up and either this node was last the owner or auto-join is on, switching between
 * [MeshMode.CLIENT] and [MeshMode.OWNER] in-process via [SyncBus.mode] rather than being
 * started and stopped on every role change. That in-process switch is the fix: Android refuses
 * `startForegroundService` from a background caller (a failover attempt running inside
 * [AutoJoin]'s loop threw `ForegroundServiceStartNotAllowedException` on-device), so once this
 * service is running at all, taking over or stepping down only ever flips [SyncBus.mode].
 * CLIENT mode holds no server socket, wake/WiFi locks or mDNS advertisement; OWNER mode is
 * exactly the pre-v13 owner behaviour (bind, advertise, serve loop, locks, step-down check).
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
        startForegroundCompat(SyncBus.mode.value)
        SyncBus.serviceRunning.value = true
        // A second start (launch restore racing a UI request) must not launch a second loop.
        if (loopJob?.isActive == true) return START_STICKY

        val flRuntime = applicationContext.services().flRuntime.value
        if (flRuntime == null) {
            Logx.w("FlSyncService: flRuntime not ready yet, stopping")
            SyncBus.serviceRunning.value = false
            stopSelf()
            return START_STICKY
        }

        loopJob = scope.launch {
            while (isActive) {
                when (SyncBus.mode.value) {
                    MeshMode.OWNER -> runOwnerCycle(flRuntime)
                    MeshMode.CLIENT -> {
                        releaseOwnerResources()
                        updateNotification(MeshMode.CLIENT)
                        // Idle until something (UI, AutoJoin, Failover) asks for OWNER again, or
                        // the service is stopped outright; poll cheaply either way.
                        withTimeoutOrNull(1000) { SyncBus.mode.first { it == MeshMode.OWNER } }
                    }
                }
            }
        }

        return START_STICKY
    }

    /**
     * One owner accept/merge cycle. Binds the server socket and takes the locks/advertisement
     * the first time this loop sees OWNER mode (or after a step-down and a later re-promotion);
     * otherwise behaves exactly as the pre-v13 owner loop did. Returns as soon as one
     * [FedAvgCoordinator.serveOnce] window elapses, so the outer loop re-checks [SyncBus.mode]
     * (and thus notices a step-down or an external "Stop") at least that often.
     */
    private suspend fun runOwnerCycle(flRuntime: FlRuntime) {
        val socket = server ?: run {
            val manager = WifiDirectManager(applicationContext)
            wifiManager = manager
            manager.start()
            val bound = bindServerSocket(manager)
            if (bound == null) {
                // Port already in use or similar: can't actually serve, fall back to CLIENT
                // rather than spin forever claiming OWNER with no listening socket.
                manager.stop()
                wifiManager = null
                SyncBus.mode.value = MeshMode.CLIENT
                return
            }
            server = bound
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jugaad:fl-sync")
                ?.apply { acquire() }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jugaad:fl-wifi")
                ?.apply { acquire() }
            flRuntime.config.value.let { c ->
                applicationContext.services().lanDiscovery.advertise(c.name, c.deviceId, bound.localPort)
            }
            // A fresh transition into OWNER: a stale failure streak from a previous CLIENT
            // stint must not survive the role change (v13 plan §5).
            if (flRuntime.config.value.consecutiveSyncFailures != 0) {
                flRuntime.updateConfig { it.copy(consecutiveSyncFailures = 0) }
            }
            SyncBus.serving.value = true
            updateNotification(MeshMode.OWNER)
            bound
        }

        val coordinator = FedAvgCoordinator(RuntimePeer(flRuntime))
        val lan = applicationContext.services().lanDiscovery
        val result = coordinator.serveOnce(socket)
        if (result.peers > 0) {
            SyncBus.last.value = result
            if (flRuntime.config.value.lastRole != NodeRole.OWNER) {
                flRuntime.updateConfig { it.copy(lastRole = NodeRole.OWNER) }
            }
        }
        checkStepDown(flRuntime, lan)
    }

    /**
     * Waits for [WifiDirectManager.group] to report formed (connection info is already
     * requested as part of [WifiDirectManager.start]); if it never forms, creates one and
     * retries with [com.jugaad.agent.core.config.Sync.retryBackoffMs] before giving up and
     * binding the listening socket anyway (v4 plan §4). Null only if the socket itself
     * can't be opened (port already in use).
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
            null
        }
    }

    /**
     * Owner-side flap guard around [Failover.shouldStepDown]: only currently advertised owners
     * are candidates (nobody but an owner calls [LanDiscovery.advertise]), and a candidate must
     * show up on two checks [com.jugaad.agent.core.config.Sync.autoJoinIntervalMs] apart AND
     * answer [OwnerProbe.ping] with its own deviceId before this phone yields to it (v13 plan
     * §2), so neither one stray/late mDNS resolve nor a frozen owner's still-resolvable mDNS
     * record (H2 in the v13 plan) flips ownership. Persists CLIENT and the winner's address with
     * the failure streak reset (v13 §5), then flips [SyncBus.mode] to CLIENT (v13 §7): the
     * service keeps running, idle, instead of stopping itself, so a later takeover never needs
     * `startForegroundService` from the background.
     */
    private suspend fun checkStepDown(flRuntime: FlRuntime, lan: LanDiscovery) {
        val now = System.currentTimeMillis()
        val intervalMs = ConfigStore.effective.value.sync.autoJoinIntervalMs.toLong()
        if (now - lastStepDownCheckMs < intervalMs) return
        lastStepDownCheckMs = now

        val myId = flRuntime.config.value.deviceId
        val others = lan.peers.value.filterNot { it.deviceId == myId }
        val candidate = others.filter { it.deviceId < myId }.minByOrNull { it.deviceId }

        if (candidate == null || candidate.deviceId != pendingStepDownId) {
            pendingStepDownId = candidate?.deviceId
            return
        }
        // Second consecutive check naming the same candidate: confirm it actually answers
        // before trusting it, so a frozen owner (mDNS record alive, process not) never wins.
        val liveId = OwnerProbe.ping(candidate.host, candidate.port)
        if (liveId == null || !Failover.shouldStepDown(myId, listOf(liveId))) {
            pendingStepDownId = null
            return
        }

        Logx.i("owner: yielding to ${candidate.name}, lower id")
        flRuntime.updateConfig { it.copy(lastRole = NodeRole.CLIENT, lastOwnerAddress = candidate.host, consecutiveSyncFailures = 0) }
        SyncBus.mode.value = MeshMode.CLIENT
    }

    /**
     * Tears down everything OWNER mode holds; safe to call repeatedly (idempotent, via the guard
     * below) since the mesh loop calls it on every CLIENT-mode iteration but must only actually
     * act on the transition out of OWNER. Also drops this phone's WiFi Direct group if it is the
     * owner of one (D4 in the v14 plan): otherwise the group stays formed and joinable with
     * nobody serving behind it, after a step-down, a "Stop", or a takeover error.
     */
    private fun releaseOwnerResources() {
        val hadOwnerResources = server != null || wifiManager != null || wakeLock != null || wifiLock != null || SyncBus.serving.value
        if (!hadOwnerResources) return
        try {
            server?.close()
        } catch (e: IOException) {
            Logx.w("FlSyncService: error closing server socket", e)
        }
        server = null
        val leavingManager = wifiManager
        wifiManager = null
        applicationContext.services().lanDiscovery.stopAdvertising()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        pendingStepDownId = null
        SyncBus.serving.value = false

        // Runs on its own short-lived scope, independent of this service's `scope`, so it still
        // completes even when this is called from onDestroy right before `scope.cancel()`. Works
        // even when this phone never held its own manager for the current group (e.g. one
        // Failover.takeOver created with a short-lived manager of its own): removeGroupIfOwner
        // reads live connection info fresh rather than relying on a cached field here.
        CoroutineScope(Dispatchers.IO).launch {
            val manager = leavingManager ?: WifiDirectManager(applicationContext)
            try {
                manager.removeGroupIfOwner()
            } finally {
                manager.stop()
            }
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        releaseOwnerResources()
        scope.cancel()
        SyncBus.serviceRunning.value = false
        super.onDestroy()
    }

    private fun startForegroundCompat(mode: MeshMode) {
        val notification = buildNotification(mode)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(mode: MeshMode) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(mode))
    }

    private fun buildNotification(mode: MeshMode): Notification {
        val text = if (mode == MeshMode.OWNER) {
            "Syncing federated model over WiFi Direct. Keeps the radio awake so peers can reach this phone."
        } else {
            "Keeping this phone in the sync group. Ready to become the owner if needed."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "fl_sync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "fl_sync"
        private const val NOTIFICATION_ID = 4210

        /** Requests OWNER mode (v13 plan §7); equivalent to [requestOwner], kept as the stable
         * external entry point every existing call site (UI, [SyncWorker], role restore) uses. */
        fun start(ctx: Context) {
            requestOwner(ctx)
        }

        /**
         * Flips [SyncBus.mode] to OWNER and starts the service if it isn't already running.
         * Returns true once OWNER mode is in effect with the service actually up (already
         * running, or a start that succeeded); false if starting it was refused. Never throws:
         * a background caller hitting `ForegroundServiceStartNotAllowedException` (H6) is caught
         * and logged, and mode is put back to CLIENT so it doesn't claim OWNER with no service
         * behind it.
         */
        fun requestOwner(ctx: Context): Boolean {
            SyncBus.mode.value = MeshMode.OWNER
            if (SyncBus.serviceRunning.value) return true
            return try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FlSyncService::class.java))
                true
            } catch (e: Exception) {
                Logx.w("mesh: service start refused: ${e.message}")
                SyncBus.mode.value = MeshMode.CLIENT
                false
            }
        }

        /** Ensures the mesh service is running in CLIENT mode (v13 plan §7): called only from
         * the foreground app-launch path (see `ServiceLocator.warmUp`) when auto-join is on, so
         * a later in-background takeover only ever needs an in-process [SyncBus.mode] flip. */
        fun ensureClientRunning(ctx: Context) {
            SyncBus.mode.value = MeshMode.CLIENT
            if (SyncBus.serviceRunning.value) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FlSyncService::class.java))
            } catch (e: Exception) {
                Logx.w("mesh: service start refused: ${e.message}")
            }
        }

        /** Requests CLIENT mode; stops the service outright only if auto-join is off (otherwise
         * it keeps running, idle, ready to take OWNER again without a background service start). */
        fun stop(ctx: Context) {
            SyncBus.mode.value = MeshMode.CLIENT
            if (SyncBus.serviceRunning.value && !ConfigStore.effective.value.sync.autoJoin) {
                ctx.stopService(Intent(ctx, FlSyncService::class.java))
            }
        }
    }
}
