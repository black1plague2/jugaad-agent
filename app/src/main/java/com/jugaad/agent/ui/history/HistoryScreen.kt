package com.jugaad.agent.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.di.ServiceLocator
import com.jugaad.agent.domain.model.Diagnosis
import com.jugaad.agent.ui.common.StatusPill
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.InkCard
import com.jugaad.agent.ui.theme.InkLine
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
import com.jugaad.agent.ui.vmFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
                title = { Text("History", color = TextHi) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextHi)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (history.isEmpty()) {
                item { Text("No diagnoses yet.", color = TextMid) }
            }
            items(history, key = { it.id }) { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(InkCard)
                        .border(1.dp, InkLine, RoundedCornerShape(12.dp))
                        .clickable { onOpen(d.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(d.timestampMs)),
                            color = TextHi, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append("score %.2f".format(d.anomalyScore))
                                d.faultClass?.let { append("  ·  ${it.label}") }
                            },
                            color = TextMid,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    StatusPill(d.status)
                }
            }
        }
    }
}
