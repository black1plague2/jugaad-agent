package com.jugaad.agent.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.core.config.MachineCatalog
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.domain.model.MachineStatus
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.FioriObjectCell
import com.jugaad.agent.ui.common.fiori.FioriSectionHeader
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.common.fiori.StatusChip
import com.jugaad.agent.ui.common.fiori.semantic
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetListScreen(
    onOpenAsset: (String) -> Unit,
    onCreate: () -> Unit,
    onLearn: () -> Unit,
) {
    val services = LocalContext.current.services()
    val vm: AssetListViewModel = viewModel(factory = vmFactory { AssetListViewModel(services) })
    val assets by vm.assets.collectAsStateWithLifecycle()
    val engine by vm.engineStatus.collectAsStateWithLifecycle()
    val lastStatus by vm.lastStatus.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = onLearn) {
                        Icon(Icons.Outlined.Hub, contentDescription = "Federated network", tint = FioriColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                PrimaryButton(
                    text = "New equipment",
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Add,
                )
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text("Jugaad Agent", style = MaterialTheme.typography.headlineMedium, color = FioriColors.TextPrimary)
                    Text(
                        "Offline condition monitoring",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FioriColors.TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    EngineBanner(
                        socName = vm.socName,
                        cnn = engine.cnnBackendLabel,
                        cnnReady = engine.cnnReady,
                        gemmaReady = engine.gemmaReady,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                FioriSectionHeader(title = "Equipment")
            }

            if (assets.isEmpty()) {
                item {
                    FioriEmptyState(
                        title = "No equipment yet",
                        body = "Tap New equipment, name the machine, then capture a reference measurement.",
                    )
                }
            }

            items(assets, key = { it.id }) { asset ->
                AssetRow(asset = asset, lastStatus = lastStatus[asset.id], onClick = { onOpenAsset(asset.id) })
            }
        }
    }
}

@Composable
private fun EngineBanner(socName: String, cnn: String, cnnReady: Boolean, gemmaReady: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FioriColors.Surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("DEVICE", color = FioriColors.TextDisabled, style = MaterialTheme.typography.labelMedium)
            Text(socName, color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        }
        // Weighted like the left Column, otherwise this side is measured first at its intrinsic
        // width and starves the device name column, forcing it to wrap mid-word.
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                "CNN · $cnn",
                color = if (cnnReady) FioriColors.Positive else FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (gemmaReady) "LLM · Gemma ready" else "LLM · template",
                color = if (gemmaReady) FioriColors.Positive else FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AssetRow(asset: Asset, lastStatus: MachineStatus?, onClick: () -> Unit) {
    val created = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(asset.createdAtMs))
    val typeLabel = MachineCatalog.byId(asset.machineTypeId).label
    FioriObjectCell(
        title = asset.name,
        subtitle = "$typeLabel · created $created",
        status = {
            if (lastStatus != null) {
                StatusChip(text = lastStatus.label, semantic = lastStatus.semantic())
            } else {
                StatusChip(text = "No readings yet", semantic = Semantic.NEUTRAL)
            }
        },
        trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null, tint = FioriColors.TextSecondary) },
        onClick = onClick,
    )
}
