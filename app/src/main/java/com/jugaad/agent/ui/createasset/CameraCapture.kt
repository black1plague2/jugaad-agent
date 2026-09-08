package com.jugaad.agent.ui.createasset

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jugaad.agent.core.Logx

/**
 * A CameraX preview + a handle to grab a single JPEG frame for the nameplate photo.
 */
class NameplateCameraController(private val context: Context) {
    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    fun capture(onResult: (ByteArray?) -> Unit) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = try {
                        val buf = image.planes[0].buffer
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    } catch (t: Throwable) {
                        Logx.e("nameplate capture decode failed", t); null
                    } finally {
                        image.close()
                    }
                    onResult(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    Logx.e("nameplate capture failed", exception)
                    onResult(null)
                }
            },
        )
    }
}

@Composable
fun CameraPreview(
    controller: NameplateCameraController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { previewView },
        update = { view ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        controller.imageCapture,
                    )
                }.onFailure { Logx.e("camera bind failed", it) }
            }, ContextCompat.getMainExecutor(context))
        },
    )
}
