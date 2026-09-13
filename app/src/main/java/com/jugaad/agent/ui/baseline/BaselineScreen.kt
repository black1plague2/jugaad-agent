package com.jugaad.agent.ui.baseline

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.CaptureOverlay
import com.jugaad.agent.ui.common.KeepScreenOn
import com.jugaad.agent.ui.common.PreRollOverlay
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.WaveformView
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
@Composable
fun BaselineScreen(
    assetId: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val services = LocalContext.current.services()
    val vm: BaselineViewModel = viewModel(factory = vmFactory { BaselineViewModel(services, assetId) })
    val phase by vm.phase.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val preRollSecondsLeft by vm.preRollSecondsLeft.collectAsStateWithLifecycle()

    KeepScreenOn(keepOn = preRollSecondsLeft != null || progress.running)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Capture reference measurement", style = MaterialTheme.typography.headlineMedium, color = FioriColors.TextPrimary)
                Text(
                    "Record 3 short clips while the machine is running normally. Rest the phone flat on the housing.",
                    color = FioriColors.TextSecondary,
                )

                SectionCard {
                    WaveformView(peaks = progress.waveform, modifier = Modifier.fillMaxWidth())
                    when (val p = phase) {
                        is BaselineViewModel.Phase.Idle ->
                            Text("Ready. Tap start when the machine is healthy and running.", color = FioriColors.TextSecondary)
                        is BaselineViewModel.Phase.Capturing ->
                            Text("Capturing clip ${p.clip} of ${p.total}", color = FioriColors.TextPrimary)
                        is BaselineViewModel.Phase.Processing ->
                            Text("Processing clip ${p.clip} of ${p.total}", color = FioriColors.TextPrimary)
                        is BaselineViewModel.Phase.Done -> {
                            Text("Reference measurement saved", color = FioriColors.Positive, fontWeight = FontWeight.Bold)
                            Text("Healthy spread %.4f, IMU index %.3f".format(p.spread, p.imuIndex), color = FioriColors.TextSecondary)
                        }
                        is BaselineViewModel.Phase.Error ->
                            Text(p.message, color = FioriColors.Negative)
                    }
                }

                Box(Modifier.weight(1f))

                when (phase) {
                    is BaselineViewModel.Phase.Done -> {
                        PrimaryButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
                    }
                    is BaselineViewModel.Phase.Error -> {
                        PrimaryButton(text = "Retry", onClick = { vm.reset(); vm.start() }, modifier = Modifier.fillMaxWidth())
                    }
                    is BaselineViewModel.Phase.Idle -> {
                        PrimaryButton(text = "Start reference measurement", onClick = vm::start, modifier = Modifier.fillMaxWidth())
                    }
                    else -> Unit
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back", color = FioriColors.TextSecondary)
                }
            }

            if (preRollSecondsLeft != null) {
                PreRollOverlay(
                    secondsLeft = preRollSecondsLeft ?: 0,
                    onCancel = vm::cancelPreRoll,
                )
            } else if (progress.running) {
                CaptureOverlay(
                    progress = progress,
                    label = when (val p = phase) {
                        is BaselineViewModel.Phase.Capturing -> "Reference measurement clip ${p.clip} / ${p.total}"
                        else -> "Reference measurement"
                    },
                )
            }
        }
    }
}
