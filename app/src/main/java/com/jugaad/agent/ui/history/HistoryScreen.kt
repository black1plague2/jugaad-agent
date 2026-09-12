package com.jugaad.agent.ui.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.ui.common.SpectrogramImage
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.StatusChip
import com.jugaad.agent.ui.common.fiori.semantic
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryViewModel(services: ServiceLocator, assetId: String) : ViewModel() {
    val history: StateFlow<List<Diagnosis>> =
        services.diagnosisRepository.observeHistory(assetId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    assetId: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val services = LocalContext.current.services()
    val vm: HistoryViewModel = viewModel(factory = vmFactory { HistoryViewModel(services, assetId) })
    val history by vm.history.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Measurement history", color = FioriColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = FioriColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    FioriEmptyState(
                        title = "No measurement documents yet",
                        body = "Take a reading to create the first measurement document.",
                    )
                }
            }
            items(history, key = { it.id }) { d ->
                HistoryRow(assetId = assetId, diagnosis = d, onClick = { onOpen(d.id) })
            }
        }
    }
}

@Composable
private fun HistoryRow(assetId: String, diagnosis: Diagnosis, onClick: () -> Unit) {
    val services = LocalContext.current.services()
    val bitmap by produceState<Bitmap?>(initialValue = null, diagnosis.id) {
        value = withContext(Dispatchers.IO) {
            diagnosis.spectrogramPng?.let { rel ->
                val f = services.assetRepository.resolve(assetId, rel)
                if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SpectrogramImage(
            bitmap = bitmap,
            animate = false,
            modifier = Modifier.width(64.dp).height(40.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(diagnosis.timestampMs)),
                color = FioriColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                buildString {
                    append("score %.2f".format(diagnosis.anomalyScore))
                    diagnosis.faultClass?.let { append("  ·  ${it.label}") }
                },
                color = FioriColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StatusChip(text = diagnosis.status.label, semantic = diagnosis.status.semantic())
    }
}
