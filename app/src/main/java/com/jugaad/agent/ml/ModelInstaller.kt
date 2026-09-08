package com.jugaad.agent.ml

import android.content.Context
import com.jugaad.agent.core.Constants
import com.jugaad.agent.core.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ExecuTorch / MediaPipe want a real filesystem path (they mmap the model), but
 * APK assets are only readable through an InputStream. This copies any bundled
 * model out of `assets/models/` into `filesDir/models/` once.
 *
 * Bundle the files under `app/src/main/assets/models/` (see ml/README.md):
 *   fault_cnn_xnnpack.pte      required for the CNN CPU path
 *   fault_cnn_qnn.pte          optional, Snapdragon HTP path
 *   gemma3-1b-it-int4.task     optional, on-device advice
 *
 * The app is fully functional with NONE of them present.
 */
class ModelInstaller(private val context: Context) {

    val modelsDir: File by lazy { File(context.filesDir, Constants.MODELS_DIR).apply { mkdirs() } }

    data class Installed(
        val cnnXnnpack: File?,
        val cnnQnn: File?,
        val gemma: File?,
    )

    suspend fun install(): Installed = withContext(Dispatchers.IO) {
        Installed(
            cnnXnnpack = copyIfPresent("models/fault_cnn_xnnpack.pte", "fault_cnn_xnnpack.pte"),
            cnnQnn = copyIfPresent("models/fault_cnn_qnn.pte", "fault_cnn_qnn.pte"),
            gemma = copyIfPresent("models/gemma3-1b-it-int4.task", "gemma3-1b-it-int4.task"),
        )
    }

    private fun copyIfPresent(assetPath: String, outName: String): File? {
        val assets = try {
            context.assets.list("models")?.toList().orEmpty()
        } catch (_: Exception) { emptyList() }
        if (assets.none { assetPath.endsWith(it) }) {
            Logx.i("model asset absent: $assetPath")
            return null
        }
        val out = File(modelsDir, outName)
        return try {
            if (out.exists() && out.length() > 0) return out
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Logx.i("installed model $outName (${out.length()} bytes)")
            out
        } catch (t: Throwable) {
            Logx.w("copy $assetPath failed", t)
            null
        }
    }
}
