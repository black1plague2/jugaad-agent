package com.jugaad.agent.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.fl.EventType
import com.jugaad.agent.fl.FlVariants
import com.jugaad.agent.fl.NodeMode
import com.jugaad.agent.fl.VariantKind
import com.jugaad.agent.ui.common.fiori.FioriBanner
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriEmptyState
import com.jugaad.agent.ui.common.fiori.FioriKeyValueRow
import com.jugaad.agent.ui.common.fiori.FioriObjectCell
import com.jugaad.agent.ui.common.fiori.FioriSectionHeader
import com.jugaad.agent.ui.common.fiori.FioriStatusLabel
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.SectionTitle
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory

/**
 * Group settings (reached from Network's Group settings ghost button): node identity, the
 * effective configuration, resilience/self-healing status and leaving the group. Carries
 * forward the old TrainingTab's IdentitySection/ConfigurationSection/ResilienceSection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val vm: NetworkViewModel = viewModel(factory = vmFactory { NetworkViewModel(services, ctx.applicationContext) })
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = FioriColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Group settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FioriColors.Background),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            IdentitySection(ui, vm)
            ConfigurationSection(ui, onResetOverrides = { vm.resetConfigOverrides() })
            ResilienceSection(ui, vm)
            GhostButton(text = "Leave group", onClick = { vm.removeGroup() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentitySection(ui: NetworkUiState, vm: NetworkViewModel) {
    SectionTitle("Identity")

    var name by remember(ui.config?.name) { mutableStateOf(ui.config?.name ?: "") }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; vm.setName(it) },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    val mode = ui.config?.mode ?: NodeMode.STABLE
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == NodeMode.EXPERIMENTAL,
            onClick = { vm.setMode(NodeMode.EXPERIMENTAL) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Experimental") }
        SegmentedButton(
            selected = mode == NodeMode.STABLE,
            onClick = { vm.setMode(NodeMode.STABLE) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Stable") }
    }

    var expanded by remember { mutableStateOf(false) }
    val pinned = ui.config?.pinnedChallenger
    val selectedLabel = pinned?.let { id -> runCatching { FlVariants.byId(id).label }.getOrDefault(id) } ?: "Auto (assigned)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Challenger") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Auto (assigned)") }, onClick = { vm.setChallenger(null); expanded = false })
            FlVariants.challengers.forEach { spec ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(spec.label)
                            Text(
                                spec.description,
                                color = FioriColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (spec.usesUnlabelled || spec.kind == VariantKind.CENTROID) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (spec.usesUnlabelled) {
                                        FioriStatusLabel("Uses unlabelled data", Semantic.INFORMATIVE)
                                    }
                                    if (spec.kind == VariantKind.CENTROID) {
                                        FioriStatusLabel("No backprop", Semantic.INFORMATIVE)
                                    }
                                }
                            }
                        }
                    },
                    onClick = { vm.setChallenger(spec.id); expanded = false },
                )
            }
        }
    }
}

/** Every [com.jugaad.agent.core.config.AppConfig] key, its effective value and source, grouped
 * by section and collapsed by default - unchanged from the old NetworkScreen. */
@Composable
private fun ConfigurationSection(ui: NetworkUiState, onResetOverrides: () -> Unit) {
    FioriSectionHeader(
        "Configuration",
        action = { TextButton(onClick = onResetOverrides) { Text("Reset device overrides") } },
    )
    val cfg = ui.appConfig
    if (cfg == null) {
        FioriEmptyState("Configuration not loaded", "The device configuration has not finished loading yet.")
        return
    }
    val grouped = remember(cfg) { groupedConfigRows(cfg) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        grouped.forEach { (section, rows) ->
            val isExpanded = expanded[section] ?: false
            FioriObjectCell(
                title = section,
                subtitle = "${rows.size} keys",
                trailing = { Text(if (isExpanded) "Collapse" else "Expand") },
                onClick = { expanded[section] = !isExpanded },
            )
            if (isExpanded) {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEach { row ->
                        FioriKeyValueRow(row.key, "${row.value} (${row.source.name})")
                    }
                }
            }
        }
    }
}

/** Self-healing status: role, sync failures, startup recovery report, recent events, manual
 * failover - unchanged from the old NetworkScreen. */
@Composable
private fun ResilienceSection(ui: NetworkUiState, vm: NetworkViewModel) {
    SectionTitle("Resilience")

    val newestFailover = ui.network?.events?.filter { it.type == EventType.FAILOVER }?.maxByOrNull { it.ts }
    if (newestFailover != null && !ui.group.isGroupOwner &&
        System.currentTimeMillis() - newestFailover.ts < 30L * 60L * 1000L
    ) {
        val ownerName = extractNewOwnerName(newestFailover.text) ?: newestFailover.text
        FioriBanner("Owner changed to $ownerName: Discover, then Connect")
    }

    FioriKeyValueRow("Last role", ui.config?.lastRole?.name ?: "NONE")
    val failures = ui.config?.consecutiveSyncFailures ?: 0
    FioriKeyValueRow(
        "Consecutive sync failures",
        "$failures",
        valueSemantic = if (failures > 0) Semantic.CRITICAL else null,
    )

    val report = ui.recoveryReport
    if (report == null || (report.repairedFiles.isEmpty() && report.staleWeights.isEmpty())) {
        FioriEmptyState("No recovery needed", "Nothing was repaired at startup.")
    } else {
        report.repairedFiles.forEach { FioriKeyValueRow("Repaired", it) }
        report.staleWeights.forEach { FioriKeyValueRow("Stale weights", it) }
    }

    val recentEvents = ui.network?.events
        ?.filter { it.type == EventType.RECOVER || it.type == EventType.FAILOVER }
        ?.sortedByDescending { it.ts }
        ?.take(5)
        ?: emptyList()
    if (recentEvents.isEmpty()) {
        FioriEmptyState("No recovery or failover events yet", "Events appear here after a repair or an ownership change.")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            recentEvents.forEach { e -> FioriKeyValueRow(relativeTime(e.ts), e.text) }
        }
    }

    var showConfirm by remember { mutableStateOf(false) }
    PrimaryButton(
        text = "Promote this node to owner",
        onClick = { showConfirm = true },
        enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth(),
    )

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Promote this node to owner") },
            text = { Text("This device takes over as the group owner. Other devices need to Discover and Connect again.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; vm.promoteToOwner() }) { Text("Promote") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
