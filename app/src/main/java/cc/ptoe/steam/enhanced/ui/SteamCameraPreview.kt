package cc.ptoe.steam.enhanced.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode

@Composable
internal fun SteamCameraPreview(
    active: Boolean,
    scanEnabled: Boolean,
    modifier: Modifier = Modifier,
    onCode: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }
    val currentCode by rememberUpdatedState(onCode)
    val currentUnavailable by rememberUpdatedState(onUnavailable)
    val currentEnabled by rememberUpdatedState(scanEnabled)

    DisposableEffect(controller, lifecycleOwner, active) {
        val executor = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        if (active) {
            controller.setImageAnalysisAnalyzer(executor, MlKitAnalyzer(
                listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor,
            ) { result ->
                if (currentEnabled) result.getValue(scanner)?.firstOrNull { it.rawValue != null }
                    ?.rawValue?.let(currentCode)
            })
            try {
                controller.bindToLifecycle(lifecycleOwner)
                controller.initializationFuture.addListener({
                    try { controller.initializationFuture.get() } catch (_: Exception) { currentUnavailable() }
                }, executor)
            } catch (_: Exception) {
                currentUnavailable()
            }
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { PreviewView(it).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        } },
        update = { it.controller = controller },
        onRelease = { it.controller = null },
    )
}
