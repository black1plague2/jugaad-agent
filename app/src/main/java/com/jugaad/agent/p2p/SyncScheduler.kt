package com.jugaad.agent.p2p

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jugaad.agent.core.config.ConfigStore
import java.util.concurrent.TimeUnit

/** Enables/disables the periodic client sync ([com.jugaad.agent.core.config.Sync.schedulerMinutes]) and persists the toggle. */
object SyncScheduler {
    private const val WORK_NAME = "fl-sync"
    private const val PREFS_NAME = "fl"
    private const val KEY_ENABLED = "sync_enabled"

    fun enable(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val schedulerMinutes = ConfigStore.effective.value.sync.schedulerMinutes.toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(schedulerMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        setEnabledFlag(context, true)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        setEnabledFlag(context, false)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    private fun setEnabledFlag(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
