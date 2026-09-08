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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.jugaad.agent.ui.services
import com.jugaad.agent.ui.theme.Accent
import com.jugaad.agent.ui.theme.Ink
import com.jugaad.agent.ui.theme.InkLine
import com.jugaad.agent.ui.theme.StatusCritical
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid
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
                Button(
                    onClick = { controller.capture { bytes -> vm.setPhoto(bytes); showCamera = false } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                ) { Text("Capture nameplate", fontWeight = FontWeight.Bold) }
                TextButton(
                    onClick = { showCamera = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Text("Cancel") }
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New asset", style = MaterialTheme.typography.displayLarge, color = TextHi)

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text("Machine name") },
                placeholder = { Text("e.g. Exhaust Fan #3") },
                singleLine = true,
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionCard {
                Text("Nameplate photo (optional)", color = TextHi, fontWeight = FontWeight.SemiBold)
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
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, InkLine, RoundedCornerShape(12.dp)),
                        )
                    }
                    TextButton(onClick = { vm.setPhoto(null) }) { Text("Remove photo") }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (cameraGranted) showCamera = true
                            else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Text("  Take nameplate photo")
                    }
                }
            }

            state.error?.let { Text(it, color = StatusCritical) }

            Box(Modifier.weight(1f))

            Button(
                onClick = vm::create,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) {
                Text(if (state.saving) "Creating…" else "Create asset", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = TextMid)
            }
        }
    }
}
