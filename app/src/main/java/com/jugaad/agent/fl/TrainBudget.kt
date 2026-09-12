package com.jugaad.agent.fl

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.jugaad.agent.core.config.ConfigStore

/**
 * Device training budget (Decision 8, v3): caps [FlModel]'s interpreter thread count to
 * the number of cores, and tells [AutoTrainer] whether it's safe to train right now
 * (thermal throttling, low battery not charging). Thread cap, thermal ceiling and low-
 * battery floor come from [com.jugaad.agent.core.config.Budget] (`AppConfig.budget`,
 * device-local — never replicated as policy), read from [ConfigStore.effective] at
 * call time (v4 plan §1).
 */
object TrainBudget {

    data class Snapshot(
        val cores: Int,
        val threads: Int,
        val thermal: Int,
        val batteryPct: Int,
        val charging: Boolean,
        val availMemMb: Long,
        val totalMemMb: Long,
        val allowAutoTrain: Boolean,
        val reason: String,
    )

    fun snapshot(context: Context): Snapshot {
        val budget = ConfigStore.effective.value.budget
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = minOf(budget.maxThreads, cores)

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = batteryManager?.isCharging ?: true

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val availMemMb = memInfo.availMem / (1024 * 1024)
        val totalMemMb = memInfo.totalMem / (1024 * 1024)

        val thermalHot = thermal >= budget.thermalMax
        val batteryLow = batteryPct < budget.minBatteryPct && !charging
        val allowAutoTrain = !thermalHot && !batteryLow
        val reason = when {
            thermalHot -> "thermal status $thermal >= ${budget.thermalMax}"
            batteryLow -> "battery $batteryPct% < ${budget.minBatteryPct}% and not charging"
            else -> "ok"
        }

        return Snapshot(cores, threads, thermal, batteryPct, charging, availMemMb, totalMemMb, allowAutoTrain, reason)
    }
}
