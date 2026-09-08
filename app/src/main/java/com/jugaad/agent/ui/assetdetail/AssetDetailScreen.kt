package com.jugaad.agent.ui.assetdetail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.MetricRow
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.StatusPill
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.InkLine
import com.jugaad.agent.ui.theme.StatusCritical
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
import com.jugaad.agent.ui.vmFactory
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    assetId: String,
    onBack: () -> Unit,
    onCaptureBaseline: () -> Unit,
    onDiagnose: () -> Unit,
    onHistory: () -> Unit,
    onChecklist: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val vm: AssetDetailViewModel = viewModel(factory = vmFactory { AssetDetailViewModel(services, assetId) })
    val asset by vm.asset.collectAsStateWithLifecycle()
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(asset?.name ?: "Asset", color = TextHi) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextHi)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.deleteAsset(onDeleted) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StatusCritical)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val a = asset ?: return@Column

            a.nameplatePhoto?.let { rel ->
                val file = remember(rel) { File(services.assetRepository.resolve(a.id, rel).path) }
                val bmp = remember(file) {
                    if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                }
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "nameplate",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                }
            }

            SectionCard {
                Text("Baseline", color = TextHi, fontWeight = FontWeight.SemiBold)
                val b = baseline
                if (b == null) {
                    Text("Not captured yet.", color = TextMid)
                } else {
                    MetricRow("Clips", "${b.clipCount}")
                    MetricRow("Healthy spread", "%.4f".format(b.spread))
                    MetricRow("Baseline IMU index", "%.3f".format(b.imuIndexMean))
                }
                OutlinedButton(onClick = onCaptureBaseline, modifier = Modifier.fillMaxWidth()) {
                    Text(if (b == null) "Capture baseline" else "Re-capture baseline")
                }
            }

            latest?.let { d ->
                SectionCard {
                    Text("Last diagnosis", color = TextHi, fontWeight = FontWeight.SemiBold)
                    StatusPill(d.status)
                    MetricRow("Anomaly score", "%.2f".format(d.anomalyScore))
                    d.faultClass?.let { MetricRow("Fault", it.label) }
                    if (d.advice.isNotBlank()) Text(d.advice, color = TextMid)
                }
            }

            ThresholdCard(
                t1 = a.thresholds.t1.toFloat(),
                t2 = a.thresholds.t2.toFloat(),
                onChange = { t1, t2 -> vm.updateThresholds(t1.toDouble(), t2.toDouble()) },
            )

            Button(
                onClick = onDiagnose,
                enabled = baseline != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) {
                Text(
                    if (baseline == null) "Capture a baseline first" else "Diagnose now",
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(onClick = onChecklist, modifier = Modifier.fillMaxWidth()) {
                Text("Go / no-go checklist")
            }
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Diagnosis history")
            }
            Box(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ThresholdCard(t1: Float, t2: Float, onChange: (Float, Float) -> Unit) {
    var range by remember(t1, t2) { mutableStateOf(t1..t2) }
    SectionCard {
        Text("Calibration", color = TextHi, fontWeight = FontWeight.SemiBold)
        Text(
            "T1 = %.1f  ·  T2 = %.1f".format(range.start, range.endInclusive),
            color = TextMid,
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = { onChange(range.start, range.endInclusive) },
            valueRange = 0.5f..8f,
            steps = 14,
        )
        Text(
            "score ≤ T1 healthy · T1–T2 warning · > T2 critical",
            color = TextMid,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
