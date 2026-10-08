package com.intellguide.saarthi.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Encapsulates Android CameraX lifecycle binding, preview rendering,
 * and background frame analysis pipeline.
 */
@SuppressLint("NewApi", "LocalSuppress")
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val targetFps: Float = 6.0f,
    private val onFrameSampled: (imageProxy: ImageProxy, rotationDegrees: Int) -> Unit = { _, _ -> },
    private val onMetricsUpdated: (metrics: FrameMetrics) -> Unit = {}
) {

    private val tag = "CameraManager"
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var isTorchOn: Boolean = false
    var isCameraActive: Boolean = false
        private set

    /**
     * Initializes and binds CameraX Preview & ImageAnalysis use-cases.
     */
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // 1. Preview Use Case
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // 2. ImageAnalysis Use Case (with KEEP_ONLY_LATEST to avoid latency lag)
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            cameraExecutor,
                            FrameAnalyzer(
                                targetFps = targetFps,
                                onFrameSampled = onFrameSampled,
                                onMetricsUpdated = onMetricsUpdated
                            )
                        )
                    }

                // 3. Camera Selector (Back camera by default for environment navigation)
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind previous use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to Lifecycle
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                isCameraActive = true
                Log.d(tag, "CameraX successfully bound to lifecycle.")
            } catch (e: Exception) {
                Log.e(tag, "Failed to bind CameraX use cases", e)
                isCameraActive = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Toggles the LED flashlight (torch).
     */
    fun toggleTorch(enable: Boolean, onStateChanged: (Boolean) -> Unit = {}) {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            camera?.cameraControl?.enableTorch(enable)?.addListener({
                isTorchOn = enable
                onStateChanged(enable)
            }, ContextCompat.getMainExecutor(context))
        } else {
            Log.w(tag, "Device has no flashlight unit.")
            onStateChanged(false)
        }
    }

    /**
     * Stops the camera and releases background executor resources.
     */
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            isCameraActive = false
            if (!cameraExecutor.isShutdown) {
                cameraExecutor.shutdown()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping CameraX", e)
        }
    }
}
