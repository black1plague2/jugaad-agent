package com.jugaad.agent.ui.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.jugaad.agent.domain.model.Asset
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.InkCard
import com.jugaad.agent.ui.theme.InkLine
import com.jugaad.agent.ui.theme.StatusHealthy
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextLo
import com.jugaad.agent.ui.theme.TextMid
import com.jugaad.agent.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssetListScreen(
    onOpenAsset: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val services = LocalContext.current.services()
    val vm: AssetListViewModel = viewModel(factory = vmFactory { AssetListViewModel(services) })
    val assets by vm.assets.collectAsStateWithLifecycle()
    val engine by vm.engineStatus.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                containerColor = Accent,
                contentColor = MaterialTheme.colorScheme.background,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  New asset", fontWeight = FontWeight.Bold)
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text("Jugaad Agent", style = MaterialTheme.typography.displayLarge, color = TextHi)
                    Text(
                        "Offline condition monitoring",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMid,
                    )
                    Spacer(Modifier.height(12.dp))
                    EngineBanner(
                        socName = vm.socName,
                        cnn = engine.cnnBackendLabel,
                        cnnReady = engine.cnnReady,
                        gemmaReady = engine.gemmaReady,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (assets.isEmpty()) {
                item {
                    SectionCard {
                        Text("No assets yet", color = TextHi, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Tap “New asset”, name the machine, then capture a healthy baseline.",
                            color = TextMid,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(assets, key = { it.id }) { asset ->
                AssetRow(asset = asset, onClick = { onOpenAsset(asset.id) })
            }
        }
    }
}

@Composable
private fun EngineBanner(socName: String, cnn: String, cnnReady: Boolean, gemmaReady: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InkCard)
            .border(1.dp, InkLine, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("DEVICE", color = TextLo, style = MaterialTheme.typography.labelLarge)
            Text(socName, color = TextHi, fontWeight = FontWeight.SemiBold)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (cnnReady) "CNN · $cnn" else "CNN · $cnn",
                color = if (cnnReady) StatusHealthy else TextMid,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (gemmaReady) "LLM · Gemma ready" else "LLM · template",
                color = if (gemmaReady) StatusHealthy else TextMid,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AssetRow(asset: Asset, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(InkCard)
            .border(1.dp, InkLine, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(asset.name, color = TextHi, style = MaterialTheme.typography.titleLarge)
            val created = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(asset.createdAtMs))
            Text(
                if (asset.hasBaseline) "Baseline set · created $created" else "No baseline · created $created",
                color = if (asset.hasBaseline) StatusHealthy else TextMid,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMid)
        }
    }
}
