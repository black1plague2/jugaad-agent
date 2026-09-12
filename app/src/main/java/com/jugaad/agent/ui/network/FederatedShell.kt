package com.jugaad.agent.ui.network

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.fiori.BottomNav
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.NavItem
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory

private val TAB_TITLES = listOf("Network", "Devices", "Sync", "Performance")

/**
 * Bottom-nav shell for the federated learning area: one shared [NetworkViewModel] behind
 * Network / Devices / Sync / Performance tabs, replacing the old two-tab [Routes.NETWORK]
 * screen. Owns the WiFi/notification permission launchers exactly as the old screen did,
 * since every tab below may trigger a discover/connect/create-group/start-serving action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FederatedShell(onBack: () -> Unit, onOpenGroupSettings: () -> Unit) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val vm: NetworkViewModel = viewModel(factory = vmFactory { NetworkViewModel(services, ctx.applicationContext) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingServiceAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val wifiPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    val wifiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingWifiAction?.invoke()
        pendingWifiAction = null
    }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingServiceAction?.invoke()
        pendingServiceAction = null
    }

    fun runWithWifiPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, wifiPermission) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingWifiAction = action
            wifiPermLauncher.launch(wifiPermission)
        }
    }

    fun runWithServicePermission(action: () -> Unit) {
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!needsNotif) {
            action()
        } else {
            pendingServiceAction = action
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        containerColor = FioriColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(TAB_TITLES[selectedTab]) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = FioriColors.Background),
                )
                if (ui.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!ui.modelReady) {
                    Text(
                        "Model not loaded",
                        color = FioriColors.of(Semantic.CRITICAL),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        },
        bottomBar = {
            BottomNav(
                items = listOf(
                    NavItem("Network", Icons.Outlined.Hub),
                    NavItem("Devices", Icons.Outlined.Devices),
                    NavItem("Sync", Icons.Outlined.Sync),
                    NavItem("Performance", Icons.Outlined.BarChart),
                ),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val wide = maxWidth >= 720.dp
            when (selectedTab) {
                0 -> NetworkTab(
                    ui = ui,
                    vm = vm,
                    wide = wide,
                    onOpenGroupSettings = onOpenGroupSettings,
                    runWithWifiPermission = { runWithWifiPermission(it) },
                )
                1 -> DevicesTab(
                    ui = ui,
                    vm = vm,
                    runWithWifiPermission = { runWithWifiPermission(it) },
                    runWithServicePermission = { runWithServicePermission(it) },
                )
                2 -> SyncTab(ui = ui, vm = vm)
                else -> PerformanceTab(ui = ui, wide = wide)
            }
        }
    }
}
