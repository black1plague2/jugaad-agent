package com.jugaad.agent.ui.checklist

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jugaad.agent.core.Constants
import com.jugaad.agent.sensor.MotionCapture
import com.jugaad.agent.ui.common.KeepScreenOn
import com.jugaad.agent.ui.common.PreRoll
import com.jugaad.agent.ui.common.PreRollOverlay
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriObjectCell
import com.jugaad.agent.ui.common.fiori.FioriSectionHeader
import com.jugaad.agent.ui.common.fiori.FioriStatusLabel
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.StatusChip
import com.jugaad.agent.ui.services

private enum class Check { PASS, FAIL, UNKNOWN }

/** One row of the Sensors section: hardware/permission availability plus the 1 s probe result. */
private data class SensorRow(val label: String, val available: Boolean, val rateHz: Double)

@Composable
fun ChecklistScreen(
    assetId: String,
    onProceed: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val scope = rememberCoroutineScope()

    val micGranted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val hasAccel = services.imuCapture.isAvailable()

    var resting by remember { mutableStateOf(Check.UNKNOWN) }
    var hasBaseline by remember { mutableStateOf(Check.UNKNOWN) }
    var running by remember { mutableStateOf(false) }

    // The "resting still" check reads the accelerometer while the technician is expected to have
    // let go of the phone, so "Run checks" gets the same get-ready countdown as a real recording.
    // Tied to rememberCoroutineScope() (not a ViewModel, this screen doesn't have one) so leaving
    // the screen cancels it and no probe runs after navigating away.
    val preRoll = remember { PreRoll(scope, "resting check") }
    val preRollSecondsLeft by preRoll.secondsLeft.collectAsStateWithLifecycle()

    fun runChecks() {
        running = true
        preRoll.start {
            hasBaseline = if (services.assetRepository.getBaseline(assetId) != null) Check.PASS else Check.FAIL
            resting = if (services.imuCapture.isRestingStill()) Check.PASS else Check.FAIL
            running = false
        }
    }

    fun cancelChecks() {
        preRoll.cancel()
        running = false
    }

    // The reference measurement is a stored file, not a live probe like "resting still", so it
    // does not need the technician to tap "Run checks" first: load it as soon as this screen
    // opens, same as the sensor probe below. Previously this stayed "Pending" until that tap even
    // for equipment with a baseline already on disk.
    LaunchedEffect(assetId) {
        hasBaseline = if (services.assetRepository.getBaseline(assetId) != null) Check.PASS else Check.FAIL
    }

    // Sensors section: availability up front, measured rate after a 1 s probe run once on
    // entering this screen. The probe is a plain LaunchedEffect(Unit) coroutine, so leaving
    // the screen before it finishes cancels it and MotionCapture unregisters its listeners.
    val motionCapture = remember { MotionCapture(ctx.applicationContext) }
    val accelAvailable = remember { motionCapture.isAvailable(Sensor.TYPE_ACCELEROMETER) }
    val gyroAvailable = remember { motionCapture.isAvailable(Sensor.TYPE_GYROSCOPE) }
    val magAvailable = remember { motionCapture.isAvailable(Sensor.TYPE_MAGNETIC_FIELD) }
    var probing by remember { mutableStateOf(true) }
    var sensorRows by remember {
        mutableStateOf(
            listOf(
                SensorRow("Microphone", micGranted, 0.0),
                SensorRow("Accelerometer", accelAvailable, 0.0),
                SensorRow("Gyroscope", gyroAvailable, 0.0),
                SensorRow("Magnetometer", magAvailable, 0.0),
            )
        )
    }
    LaunchedEffect(Unit) {
        val reading = runCatching { motionCapture.capture(1) }.getOrNull()
        sensorRows = listOf(
            SensorRow("Microphone", micGranted, Constants.SAMPLE_RATE_HZ.toDouble()),
            SensorRow("Accelerometer", accelAvailable, reading?.accelRateHz ?: 0.0),
            SensorRow("Gyroscope", gyroAvailable, reading?.gyroRateHz ?: 0.0),
            SensorRow("Magnetometer", magAvailable, reading?.magRateHz ?: 0.0),
        )
        probing = false
    }

    // Created once at screen level (not re-entered inside runChecks/the probe effect) so the
    // 1 s sensor probe's state update recomposes SensorsSection without resetting scroll offset.
    val scrollState = rememberScrollState()

    KeepScreenOn(keepOn = preRollSecondsLeft != null)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Pre-check", style = MaterialTheme.typography.headlineMedium, color = FioriColors.TextPrimary)
            Text("Confirm the setup before taking a reading.", color = FioriColors.TextSecondary)

            SectionCard {
                CheckRow("Microphone permission", if (micGranted) Check.PASS else Check.FAIL)
                CheckRow("Accelerometer present", if (hasAccel) Check.PASS else Check.FAIL)
                CheckRow("Reference measurement captured for this equipment", hasBaseline)
                CheckRow("Phone resting still on the housing", resting)
            }

            Text(
                "Tip: press the phone flat against a solid part of the machine frame, not a panel or guard.",
                color = FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            SensorsSection(sensorRows, probing)

            Spacer(Modifier.height(24.dp))

            GhostButton(
                text = if (running) "Checking" else "Run checks",
                onClick = { runChecks() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )

            val ready = micGranted && hasAccel && hasBaseline == Check.PASS && resting == Check.PASS
            PrimaryButton(
                text = "Proceed to take reading",
                onClick = onProceed,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back", color = FioriColors.TextSecondary)
            }
        }

        preRollSecondsLeft?.let { seconds ->
            PreRollOverlay(secondsLeft = seconds, onCancel = ::cancelChecks)
        }
        }
    }
}

@Composable
private fun CheckRow(label: String, state: Check) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FioriColors.TextPrimary, modifier = Modifier.weight(1f))
        val (text, semantic) = when (state) {
            Check.PASS -> "Pass" to Semantic.POSITIVE
            Check.FAIL -> "Fail" to Semantic.NEGATIVE
            Check.UNKNOWN -> "Pending" to Semantic.NEUTRAL
        }
        StatusChip(text = text, semantic = semantic)
    }
}

@Composable
private fun SensorsSection(rows: List<SensorRow>, probing: Boolean) {
    FioriSectionHeader("Sensors")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            val (statusText, statusSemantic) = if (row.available) "Available" to Semantic.POSITIVE else "Not available" to Semantic.NEUTRAL
            FioriObjectCell(
                title = row.label,
                subtitle = sensorSubtitle(row, probing),
                status = { FioriStatusLabel(statusText, statusSemantic) },
            )
        }
    }
    Text(
        "Barometer, light and proximity are not used",
        color = FioriColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun sensorSubtitle(row: SensorRow, probing: Boolean): String = when {
    !row.available -> "Not available, feature set to 0"
    probing -> "Measuring"
    else -> "%.1f Hz".format(row.rateHz)
}
