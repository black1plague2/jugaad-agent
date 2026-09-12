package com.jugaad.agent.ui.assetdetail

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.core.config.ConfigStore
import com.jugaad.agent.core.config.MachineCatalog
import com.jugaad.agent.ui.common.MetricRow
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.StatusPill
import com.jugaad.agent.ui.common.fiori.FioriBanner
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.FioriKeyValueRow
import com.jugaad.agent.ui.common.fiori.FioriSectionHeader
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.MiniStatCard
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.semantic
import com.jugaad.agent.ui.services
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
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val calibrateMessage by vm.calibrateMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(calibrateMessage) {
        calibrateMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissCalibrateMessage()
        }
    }

    // Baseline/latest reading are one-shot loads (AssetDetailViewModel.refresh), so returning
    // from Reference measurement capture (or History) needs an explicit reload on resume,
    // otherwise this screen keeps showing "Not captured yet" until it is recreated.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(asset?.name ?: "Equipment", color = FioriColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = FioriColors.TextPrimary)
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
                .padding(20.dp),
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
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
            }

            Text(
                MachineCatalog.byId(a.machineTypeId).label,
                color = FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bench / test equipment", color = FioriColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Switch(checked = a.benchTest, onCheckedChange = { vm.setBenchTest(it) })
            }
            if (a.benchTest) {
                FioriBanner(
                    "Bench equipment: readings are not used for training, sharing or calibration",
                    Semantic.INFORMATIVE,
                )
            }

            val d = latest
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStatCard(
                    label = "Last reading",
                    value = d?.status?.label ?: "No readings",
                    sub = d?.let { relativeTime(it.timestampMs) },
                    subSemantic = d?.status?.semantic() ?: Semantic.NEUTRAL,
                    modifier = Modifier.weight(1f),
                )
                MiniStatCard(
                    label = "Thresholds",
                    value = "%.1f / %.1f".format(a.thresholds.t1, a.thresholds.t2),
                    sub = "T1 / T2",
                    modifier = Modifier.weight(1f),
                )
                MiniStatCard(
                    label = "Calibration",
                    value = calibration?.method ?: "Not calibrated",
                    modifier = Modifier.weight(1f),
                )
            }

            PrimaryButton(
                text = "Take reading",
                onClick = onDiagnose,
                enabled = baseline != null,
                modifier = Modifier.fillMaxWidth(),
            )

            GhostButton(text = "Reference measurement", onClick = onCaptureBaseline, modifier = Modifier.fillMaxWidth())
            GhostButton(text = "Pre-check", onClick = onChecklist, modifier = Modifier.fillMaxWidth())
            GhostButton(text = "Measurement history", onClick = onHistory, modifier = Modifier.fillMaxWidth())

            SectionCard {
                Text("Reference measurement", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                val b = baseline
                if (b == null) {
                    Text("Not captured yet.", color = FioriColors.TextSecondary)
                } else {
                    MetricRow("Clips", "${b.clipCount}")
                    MetricRow("Healthy spread", "%.4f".format(b.spread))
                    MetricRow("Reference IMU index", "%.3f".format(b.imuIndexMean))
                }
            }

            d?.let {
                SectionCard {
                    Text("Last measurement document", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    StatusPill(it.status)
                    MetricRow("Anomaly score", "%.2f".format(it.anomalyScore))
                    it.faultClass?.let { fc -> MetricRow("Fault", fc.label) }
                    if (it.advice.isNotBlank()) Text(it.advice, color = FioriColors.TextSecondary)
                }
            }

            if (!a.benchTest) {
                ThresholdCard(
                    t1 = a.thresholds.t1.toFloat(),
                    t2 = a.thresholds.t2.toFloat(),
                    onChange = { t1, t2 -> vm.updateThresholds(t1.toDouble(), t2.toDouble()) },
                )
                CalibrateSection(vm, a.thresholds.t1, a.thresholds.t2)
            }

            var showDeleteConfirm by remember { mutableStateOf(false) }
            TextButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete equipment", color = FioriColors.Negative)
            }
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete ${a.name}") },
                    text = { Text("This removes ${a.name} and all of its measurement history. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = { showDeleteConfirm = false; vm.deleteAsset(onDeleted) }) {
                            Text("Delete", color = FioriColors.Negative)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    },
                )
            }
            Box(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ThresholdCard(t1: Float, t2: Float, onChange: (Float, Float) -> Unit) {
    var range by remember(t1, t2) { mutableStateOf(t1..t2) }
    SectionCard {
        Text("Calibration", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            "T1 = %.1f  T2 = %.1f".format(range.start, range.endInclusive),
            color = FioriColors.TextSecondary,
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = { onChange(range.start, range.endInclusive) },
            valueRange = 0.5f..8f,
            steps = 14,
        )
        Text(
            "score up to T1 healthy, T1 to T2 warning, above T2 critical",
            color = FioriColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Decision 9 / v4: adaptive robust calibration, drift banner, reference-measurement refresh. */
@Composable
private fun CalibrateSection(vm: AssetDetailViewModel, t1: Double, t2: Double) {
    FioriSectionHeader("Calibrate thresholds")
    if (!vm.calibrationAvailable) {
        FioriEmptyState(
            "Calibration unavailable",
            "The federated learning store has not finished loading yet.",
        )
        return
    }
    val record by vm.calibration.collectAsStateWithLifecycle()
    val busy by vm.calibrating.collectAsStateWithLifecycle()
    val canRefresh by vm.canRefresh.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val cfg by ConfigStore.effective.collectAsStateWithLifecycle()
    val refreshMinHealthy = cfg.calibration.refreshMinHealthy

    FioriKeyValueRow("T1", "%.1f".format(t1))
    FioriKeyValueRow("T2", "%.1f".format(t2))

    val r = record
    if (r == null) {
        FioriEmptyState(
            "No observed samples yet",
            "Label captured measurements to calibrate thresholds from real data.",
        )
    } else {
        FioriKeyValueRow("Method", r.method)
        FioriKeyValueRow("Median healthy", "%.3f".format(r.medianHealthy))
        FioriKeyValueRow("MAD healthy", "%.3f".format(r.madHealthy))
        FioriKeyValueRow("Proposed T1", "%.2f".format(r.t1))
        FioriKeyValueRow("Proposed T2", "%.2f".format(r.t2))
        FioriKeyValueRow("Healthy count", "${r.nHealthy}")
        FioriKeyValueRow("Faulty count", "${r.nFaulty}")
        FioriKeyValueRow("Last run", relativeTime(r.lastRunMs))
        if (r.drift) {
            FioriBanner("Reference measurement drifting: refresh it")
        }
    }

    val canCalibrate = (r?.nHealthy ?: 0) >= cfg.calibration.minHealthy
    GhostButton(
        text = if (canCalibrate) "Calibrate" else "Needs ${cfg.calibration.minHealthy} or more healthy samples",
        onClick = { vm.calibrate() },
        enabled = canCalibrate && !busy,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        "$canRefresh of $refreshMinHealthy healthy readings with full features",
        color = FioriColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    GhostButton(
        text = "Refresh reference measurement",
        onClick = { vm.refreshBaseline() },
        enabled = canRefresh >= refreshMinHealthy && !refreshing,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "never"
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
