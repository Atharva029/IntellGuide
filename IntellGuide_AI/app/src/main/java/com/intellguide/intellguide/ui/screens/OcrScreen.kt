package com.intellguide.intellguide.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.intellguide.intellguide.ui.VoiceViewModel
import com.intellguide.intellguide.ui.theme.*
import com.intellguide.intellguide.vision.OCRManager
import com.intellguide.intellguide.vision.TextAlignmentGuide
import java.io.File
import java.util.concurrent.Executors

/**
 * OCR Screen — Redesigned for speed + blind-user alignment.
 *
 * Architecture:
 *   1. Live camera preview runs ML Kit text detection at ~3 FPS for a cheap check only.
 *      No full OCR or reading-order sort happens here.
 *   2. [TextAlignmentGuide] evaluates bounding-box geometry and produces audio
 *      guidance cues ("move closer", "text centered") via TTS.
 *   3. When alignment is stable for a few frames, an [ImageCapture] still is
 *      auto-triggered — one high-res photo.
 *   4. That single still gets the 1600px resize + full reading-order OCR from
 *      [OCRManager.processBitmap], and the result is spoken aloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    viewModel: VoiceViewModel,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    // ── State ────────────────────────────────────────────────────────────────

    /** The final OCR result displayed to the user. */
    var recognizedText by remember { mutableStateOf("Point camera at text to begin...") }

    /** Current guidance message shown on-screen. */
    var guidanceText by remember { mutableStateOf("Searching for text...") }

    /** Color of the reticle border — reflects alignment status. */
    var reticleColor by remember { mutableStateOf(PrimaryBlue.copy(alpha = 0.6f)) }

    /** True while waiting for the captured still to be OCR-processed. */
    var isProcessingCapture by remember { mutableStateOf(false) }

    /** True after a successful capture+OCR cycle to prevent re-triggering until reset. */
    var hasCaptured by remember { mutableStateOf(false) }

    /** Controls whether the live detection loop runs. */
    var isScanning by remember { mutableStateOf(true) }

    /** Debounce: last guidance message we spoke aloud (avoid repeating). */
    var lastSpokenGuidance by remember { mutableStateOf("") }
    var lastGuidanceTime by remember { mutableStateOf(0L) }

    val ocrManager = remember { OCRManager() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // ImageCapture use-case reference, assigned once when camera binds.
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // TextAlignmentGuide — created lazily once we know frame dimensions.
    var alignmentGuide by remember { mutableStateOf<TextAlignmentGuide?>(null) }

    // ── Google Document Scanner ──────────────────────────────────────────────
    val docScannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
            .build()
    }
    val docScanner = remember { GmsDocumentScanning.getClient(docScannerOptions) }

    val docScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanningResult?.pages
            if (!pages.isNullOrEmpty()) {
                val pageUri = pages[0].imageUri
                try {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, pageUri))
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, pageUri)
                    }
                    viewModel.speakFeedback("Document scanned successfully. Analyzing text.")
                    ocrManager.processBitmap(
                        bitmap = bitmap,
                        onTextRecognized = { extractedText ->
                            isScanning = false
                            recognizedText = extractedText
                            viewModel.speakFeedback("Scanned document result: $extractedText")
                        },
                        onError = {
                            viewModel.speakFeedback("Failed to process scanned document.")
                        }
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    viewModel.speakFeedback("Error loading scanned document image.")
                }
            }
        }
    }

    // ── Helper: speak guidance with debounce (same message <= every 3 s) ──────
    fun speakGuidance(message: String) {
        val now = System.currentTimeMillis()
        if (message != lastSpokenGuidance || now - lastGuidanceTime > 3000) {
            lastSpokenGuidance = message
            lastGuidanceTime = now
            viewModel.speakFeedback(message)
        }
    }

    // ── Helper: auto-capture a still and run full OCR once ───────────────────
    fun triggerAutoCapture() {
        val capture = imageCapture ?: return
        if (isProcessingCapture || hasCaptured) return

        isProcessingCapture = true
        guidanceText = "Capturing..."
        reticleColor = Color(0xFF10B981)  // green flash

        val photoFile = File(context.cacheDir, "ocr_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        // Load and fix rotation from EXIF
                        val rawBitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        val exif = android.media.ExifInterface(photoFile.absolutePath)
                        val orientation = exif.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        val rotationDeg = when (orientation) {
                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                        val bitmap = if (rotationDeg != 0f) {
                            val matrix = Matrix().apply { postRotate(rotationDeg) }
                            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        } else {
                            rawBitmap
                        }

                        viewModel.speakFeedback("Photo captured. Reading text.")

                        // Single full OCR pass with 1600px resize + reading-order sort
                        ocrManager.processBitmap(
                            bitmap = bitmap,
                            onTextRecognized = { extractedText ->
                                recognizedText = extractedText
                                guidanceText = "Text captured successfully!"
                                reticleColor = Color(0xFF10B981)
                                hasCaptured = true
                                isProcessingCapture = false
                                viewModel.speakFeedback(extractedText)

                                // Cleanup temp file
                                photoFile.delete()
                            },
                            onError = { _ ->
                                guidanceText = "OCR failed. Try again."
                                isProcessingCapture = false
                                hasCaptured = false
                                reticleColor = PrimaryBlue.copy(alpha = 0.6f)
                                viewModel.speakFeedback("Could not read text. Please try again.")
                                photoFile.delete()
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("OcrScreen", "Error processing captured image", e)
                        guidanceText = "Processing error. Try again."
                        isProcessingCapture = false
                        hasCaptured = false
                        reticleColor = PrimaryBlue.copy(alpha = 0.6f)
                        viewModel.speakFeedback("Processing error. Please try again.")
                        photoFile.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("OcrScreen", "Image capture failed", exception)
                    guidanceText = "Capture failed. Hold steady and try again."
                    isProcessingCapture = false
                    hasCaptured = false
                    reticleColor = PrimaryBlue.copy(alpha = 0.6f)
                    viewModel.speakFeedback("Capture failed. Hold the phone steady.")
                }
            }
        )
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        viewModel.speakFeedback("OCR Reader activated. Point your camera at text. I will guide you to align, then capture and read automatically.")
        onDispose {
            ocrManager.close()
            cameraExecutor.shutdown()
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to Dashboard",
                        tint = TextWhite
                    )
                }

                Text(
                    text = "Read Text (OCR)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = {
                    if (recognizedText.isNotBlank() && recognizedText != "Point camera at text to begin...") {
                        viewModel.speakFeedback("Recognized text: $recognizedText")
                    } else {
                        viewModel.speakFeedback("No text captured yet. Follow the audio guidance to align.")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Read Aloud",
                        tint = PrimaryBlue
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (hasCameraPermission) {
                // ── Camera Preview + Lightweight Detection + ImageCapture ────
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            // Use-case 1: Preview
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // Use-case 2: ImageAnalysis for cheap text-presence detection
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            // Use-case 3: ImageCapture for the one-shot still
                            val imgCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            imageCapture = imgCapture

                            // ── Analyzer: cheap text detection -> guidance -> auto-capture ──
                            var frameSkipCounter = 0
                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                // Throttle to ~3 FPS (skip 9 out of every 10 frames at 30 FPS)
                                frameSkipCounter++
                                if (frameSkipCounter % 10 != 0) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                // Don't analyze if we already captured or if scanning is paused
                                if (hasCaptured || !isScanning || isProcessingCapture) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                // Lazily create alignment guide with actual frame dimensions
                                if (alignmentGuide == null) {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val w: Int
                                    val h: Int
                                    if (rotation == 90 || rotation == 270) {
                                        w = imageProxy.height
                                        h = imageProxy.width
                                    } else {
                                        w = imageProxy.width
                                        h = imageProxy.height
                                    }
                                    alignmentGuide = TextAlignmentGuide(w, h)
                                }

                                // Run cheap ML Kit text detection (returns raw Text, no formatting)
                                ocrManager.processImageProxyForDetection(
                                    imageProxy = imageProxy,
                                    onTextDetected = { visionText ->
                                        val guide = alignmentGuide ?: return@processImageProxyForDetection
                                        val result = guide.evaluate(visionText)

                                        // Update UI
                                        guidanceText = result.message
                                        reticleColor = when (result.guidance) {
                                            TextAlignmentGuide.Guidance.TEXT_CENTERED ->
                                                Color(0xFF10B981)  // green
                                            TextAlignmentGuide.Guidance.HOLD_STEADY ->
                                                Color(0xFFFBBF24)  // amber
                                            else ->
                                                PrimaryBlue.copy(alpha = 0.6f)  // default blue
                                        }

                                        // Speak guidance
                                        speakGuidance(result.message)

                                        // Auto-capture when ready
                                        if (result.readyToCapture) {
                                            guide.resetStability()
                                            triggerAutoCapture()
                                        }
                                    },
                                    onError = { _ -> }
                                )
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis,
                                    imgCapture
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ── Guidance text badge at top ──────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = SurfaceDark.copy(alpha = 0.85f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (hasCaptured) Icons.Default.CheckCircle
                                              else if (isProcessingCapture) Icons.Default.HourglassTop
                                              else Icons.Default.CenterFocusStrong,
                                contentDescription = null,
                                tint = reticleColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = guidanceText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ── Center Focus Reticle Frame Overlay ──────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .fillMaxHeight(0.52f)
                        .align(Alignment.Center)
                        .border(2.dp, reticleColor, RoundedCornerShape(20.dp))
                )

                // ── Bottom Text Display Card Overlay ────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CardBorder, RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.92f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (hasCaptured) Color(0xFF10B981)
                                                else if (isProcessingCapture) Color(0xFFFBBF24)
                                                else if (isScanning) Color(0xFF10B981)
                                                else Color.Gray
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            hasCaptured -> "Captured ✓"
                                            isProcessingCapture -> "Processing..."
                                            isScanning -> "Guiding..."
                                            else -> "Paused"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                }

                                // Pause / Resume scanning toggle
                                if (!hasCaptured) {
                                    IconButton(
                                        onClick = { isScanning = !isScanning },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isScanning) "Pause" else "Resume",
                                            tint = TextWhite
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = recognizedText,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                color = TextWhite,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp, max = 120.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Re-scan button (visible after capture)
                            if (hasCaptured) {
                                Button(
                                    onClick = {
                                        hasCaptured = false
                                        isProcessingCapture = false
                                        isScanning = true
                                        recognizedText = "Point camera at text to begin..."
                                        guidanceText = "Searching for text..."
                                        reticleColor = PrimaryBlue.copy(alpha = 0.6f)
                                        lastSpokenGuidance = ""
                                        alignmentGuide?.resetStability()
                                        viewModel.speakFeedback("Ready to scan again. Point camera at text.")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = TextWhite,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scan Another Text", color = TextWhite, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Google Document Scanner Button
                            Button(
                                onClick = {
                                    isScanning = false
                                    activity?.let { act ->
                                        docScanner.getStartScanIntent(act)
                                            .addOnSuccessListener { intentSender ->
                                                docScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                            }
                                            .addOnFailureListener {
                                                viewModel.speakFeedback("Opening scanner. Keep document steady.")
                                            }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Doctor Report / Document", color = TextWhite, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    if (recognizedText.isNotBlank()) {
                                        viewModel.speakFeedback(recognizedText)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Read Aloud Again", color = TextWhite, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

            } else {
                // ── Permission Request Fallback View ────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "IntellGuide needs camera access to detect and read text from signs and documents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onRequestCameraPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text("Grant Camera Permission", color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
