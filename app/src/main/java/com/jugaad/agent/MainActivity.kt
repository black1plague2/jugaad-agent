package com.jugaad.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.jugaad.agent.ui.nav.JugaadNavGraph
import com.jugaad.agent.ui.theme.JugaadTheme
import com.jugaad.agent.ui.theme.TextHi
import com.jugaad.agent.ui.theme.TextMid

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JugaadTheme {
                RecordAudioGate {
                    val nav = rememberNavController()
                    JugaadNavGraph(nav)
                }
            }
        }
    }
}

/**
 * RECORD_AUDIO is required by every capture path, so it is gated once here. CAMERA
 * (nameplate photo) and HIGH_SAMPLING_RATE_SENSORS (install-time) are handled elsewhere.
 */
@Composable
private fun RecordAudioGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    if (granted) {
        content()
    } else {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Microphone access", style = MaterialTheme.typography.displayLarge, color = TextHi)
                Text(
                    "Jugaad Agent turns the phone into a vibration + acoustic sensor. " +
                        "It needs microphone access to listen to the machine. Nothing leaves the device.",
                    color = TextMid,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone access")
                }
            }
        }
    }
}
