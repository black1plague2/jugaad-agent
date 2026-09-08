package com.jugaad.agent.ui.baseline

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.CaptureOverlay
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.StatusCritical
import com.jugaad.agent.ui.theme.StatusHealthy
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
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

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Capture baseline", style = MaterialTheme.typography.displayLarge, color = TextHi)
                Text(
                    "Record 3 short clips while the machine is running normally. " +
                        "Rest the phone flat on the housing.",
                    color = TextMid,
                )

                SectionCard {
                    when (val p = phase) {
                        is BaselineViewModel.Phase.Idle ->
                            Text("Ready. Tap start when the machine is healthy and running.", color = TextMid)
                        is BaselineViewModel.Phase.Capturing ->
                            Text("Capturing clip ${p.clip} of ${p.total}…", color = TextHi)
                        is BaselineViewModel.Phase.Processing ->
                            Text("Processing clip ${p.clip} of ${p.total}…", color = TextHi)
                        is BaselineViewModel.Phase.Done -> {
                            Text("Baseline saved", color = StatusHealthy, fontWeight = FontWeight.Bold)
                            Text("Healthy spread %.4f · IMU index %.3f".format(p.spread, p.imuIndex), color = TextMid)
                        }
                        is BaselineViewModel.Phase.Error ->
                            Text(p.message, color = StatusCritical)
                    }
                }

                Box(Modifier.weight(1f))

                when (phase) {
                    is BaselineViewModel.Phase.Done -> {
                        Button(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Text("  Done", fontWeight = FontWeight.Bold)
                        }
                    }
                    is BaselineViewModel.Phase.Error -> {
                        Button(
                            onClick = { vm.reset(); vm.start() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                        ) { Text("Retry", fontWeight = FontWeight.Bold) }
                    }
                    is BaselineViewModel.Phase.Idle -> {
                        Button(
                            onClick = vm::start,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                        ) { Text("Start baseline capture", fontWeight = FontWeight.Bold) }
                    }
                    else -> Unit
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }

            if (progress.running) {
                CaptureOverlay(
                    progress = progress,
                    label = when (val p = phase) {
                        is BaselineViewModel.Phase.Capturing -> "Baseline clip ${p.clip} / ${p.total}"
                        else -> "Baseline"
                    },
                )
            }
        }
    }
}
