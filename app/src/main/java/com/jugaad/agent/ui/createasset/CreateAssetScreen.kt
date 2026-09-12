@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jugaad.agent.ui.createasset

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jugaad.agent.ui.common.SectionCard
import com.jugaad.agent.ui.common.fiori.FioriColors
import com.jugaad.agent.ui.common.fiori.FioriObjectCell
import com.jugaad.agent.ui.common.fiori.FioriStatusLabel
import com.jugaad.agent.ui.common.fiori.GhostButton
import com.jugaad.agent.ui.common.fiori.PrimaryButton
import com.jugaad.agent.ui.common.fiori.Semantic
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.vmFactory

@Composable
fun CreateAssetScreen(
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val services = ctx.services()
    val vm: CreateAssetViewModel = viewModel(factory = vmFactory { CreateAssetViewModel(services) })
    val state by vm.state.collectAsStateWithLifecycle()

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraGranted = ok
        showCamera = ok
    }
    val controller = remember { NameplateCameraController(ctx) }

    LaunchedEffect(state.createdAssetId) {
        state.createdAssetId?.let(onCreated)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        if (showCamera && cameraGranted) {
            Box(Modifier.fillMaxSize().padding(pad)) {
                CameraPreview(controller, Modifier.fillMaxSize())
                PrimaryButton(
                    text = "Capture nameplate",
                    onClick = { controller.capture { bytes -> vm.setPhoto(bytes); showCamera = false } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
                TextButton(
                    onClick = { showCamera = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Text("Cancel") }
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New equipment", style = MaterialTheme.typography.headlineMedium, color = FioriColors.TextPrimary)

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text("Machine name") },
                placeholder = { Text("e.g. Exhaust Fan 3") },
                singleLine = true,
                isError = state.error != null,
                colors = fioriTextFieldColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.machineQuery,
                    onValueChange = vm::setMachineQuery,
                    label = { Text("Machine type") },
                    placeholder = { Text("Search: coffee, fan, pump") },
                    singleLine = true,
                    colors = fioriTextFieldColors(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                FioriStatusLabel(state.selectedMachineTypeLabel, Semantic.INFORMATIVE)
                if (state.machineResults.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.machineResults.forEach { type ->
                            FioriObjectCell(
                                title = type.label,
                                subtitle = type.notes,
                                status = {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        type.keywords.take(6).forEach { kw ->
                                            FioriStatusLabel(kw, Semantic.NEUTRAL)
                                        }
                                    }
                                },
                                onClick = { vm.selectMachineType(type) },
                            )
                        }
                    }
                }
            }

            SectionCard {
                Text("Nameplate photo (optional)", color = FioriColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                val jpeg = state.photoJpeg
                if (jpeg != null) {
                    val bmp = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                    bmp?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "nameplate",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, FioriColors.Hairline, RoundedCornerShape(8.dp)),
                        )
                    }
                    TextButton(onClick = { vm.setPhoto(null) }) { Text("Remove photo", color = FioriColors.TextSecondary) }
                } else {
                    GhostButton(
                        text = "Take nameplate photo",
                        onClick = {
                            if (cameraGranted) showCamera = true
                            else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.CameraAlt,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Bench / test equipment (readings are not used for training)",
                    color = FioriColors.TextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(checked = state.benchTest, onCheckedChange = vm::setBenchTest)
            }

            state.error?.let { Text(it, color = FioriColors.Negative) }

            Box(Modifier.weight(1f))

            PrimaryButton(
                text = if (state.saving) "Creating" else "Create equipment",
                onClick = vm::create,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = FioriColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun fioriTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = FioriColors.InputBackground,
    unfocusedContainerColor = FioriColors.InputBackground,
    disabledContainerColor = FioriColors.InputBackground,
    focusedBorderColor = FioriColors.Brand,
    unfocusedBorderColor = FioriColors.Hairline,
    focusedTextColor = FioriColors.TextPrimary,
    unfocusedTextColor = FioriColors.TextPrimary,
    cursorColor = FioriColors.Brand,
    focusedLabelColor = FioriColors.Brand,
    unfocusedLabelColor = FioriColors.TextSecondary,
    focusedPlaceholderColor = FioriColors.TextDisabled,
    unfocusedPlaceholderColor = FioriColors.TextDisabled,
)
