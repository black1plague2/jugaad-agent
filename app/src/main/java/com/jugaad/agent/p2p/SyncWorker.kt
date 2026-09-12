package com.jugaad.agent.p2p

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jugaad.agent.core.Logx
import com.jugaad.agent.fl.NodeRole
import com.jugaad.agent.ui.services

/**
 * Periodic watchdog + sync (v4 plan §4): an OWNER that isn't currently serving restarts
 * [FlSyncService]; a CLIENT (or a not-yet-decided NONE node that happens to be sitting in
 * a formed client-side group) tries [SyncNow.asClient], which owns its own retry/backoff
 * and failover decision.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flRuntime = applicationContext.services().flRuntime.value
        if (flRuntime == null) {
            Logx.i("SyncWorker: flRuntime not ready yet, skipping")
            return Result.success()
        }

        when (flRuntime.config.value.lastRole) {
            NodeRole.OWNER -> {
                if (!SyncBus.serving.value) {
                    Logx.i("SyncWorker: role OWNER but not serving, restarting FlSyncService")
                    FlSyncService.start(applicationContext)
                }
            }

            else -> {
                // CLIENT always tries; NONE tries opportunistically (SyncNow.asClient no-ops
                // by itself when no client-side group is formed).
                if (SyncNow.asClient(applicationContext) == null) {
                    Logx.i("SyncWorker: role ${flRuntime.config.value.lastRole}, no client sync performed")
                }
            }
        }

        return Result.success()
    }
}
