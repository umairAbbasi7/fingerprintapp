package com.tcc.fingerprint.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import android.graphics.Rect
import android.media.Image
import android.media.MediaPlayer
import android.os.Handler
import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.tcc.fingerprint.compose.CaptureComposeScreen
import com.tcc.fingerprint.detection.HybridDetector
import com.tcc.fingerprint.processing.FingerprintProcessor
import com.tcc.fingerprint.quality.CombinedQualityAssessor
import com.tcc.fingerprint.ui.CaptureActivity.Companion.EXTRA_MODE
import com.tcc.fingerprint.ui.CaptureActivity.Companion.EXTRA_USER_ID
import com.tcc.fingerprint.utils.Constants.CAPTURE_HEIGHT
import com.tcc.fingerprint.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Deque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.core.graphics.scale

class CaptureFingerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CaptureFingerActivity"
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080

        const val EXTRA_MODE = "capture_mode"
        const val EXTRA_USER_ID = "user_id"
        private var consecutiveClearFrames = 2

        private const val LAP_VAR_REJECT = 20.0
        private const val LAP_VAR_PASS = 90.0
        private const val TENENGRAD_EXPECTED_MAX = 2000.0
        private const val COMBINED_ACCEPT_THRESHOLD = 0.52
        private const val REQUIRED_CONSECUTIVE_CLEAR_FRAMES = 1

        private const val STABILITY_HISTORY = 5
        private const val STABILITY_IOU_THRESHOLD = 0.5
        private const val STABILITY_SIZE_TOLERANCE = 0.30

        // near other fields
        private var readyFrameCount = 0
        private const val REQUIRED_READY_FRAMES_FOR_COUNTDOWN = 3 // start with 2
// ±30%
    }

    // CameraX
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var imageAnalyzer: ImageAnalysis
    private var camera: androidx.camera.core.Camera? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Compose/preview state
    private var previewViewSize = IntSize(0, 0)
    private var lastOverlayRectF: RectF? = null

    // Flags
    private var isAutoCaptureMode = true
    private var isCapturing by mutableStateOf(false)
    private var hasCaptured = false
    private var isMonitoringStopped = false
    private var isCountdownActive by mutableStateOf(false)

    private lateinit var hybridDetector: HybridDetector
    private var combinedQualityAssessor: CombinedQualityAssessor? = null
    private var modelLabel: String = ""
    private var qualityText by mutableStateOf("")
    private var showCaptureIndicatorState = mutableStateOf(false)
    private var previewViewRef: PreviewView? = null

    // Stability history (view-space rects)
    private val detectionHistory: ArrayDeque<RectF> = ArrayDeque()
    private var lastAssessment: CombinedQualityAssessor.QualityAssessment? = null

    // Countdown coroutine job
    private var countdownJob: Job? = null
    
    // Sound system
    private var holdSoundPlayer: MediaPlayer? = null
    private var capturedSoundPlayer: MediaPlayer? = null
    private var countdownValue by mutableStateOf(0)
    
    // Progress UI state
    private var captureStatus by mutableStateOf("Center 4 fingers on the yellow crosshairs")
    private var progressStep by mutableStateOf(0) // 0: positioning, 1: detecting, 2: capturing, 3: processing

    // Permissions
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }else{
                startCameraX(previewViewRef!!)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize detectors/assessor - same as Camera2 code
        hybridDetector = HybridDetector(this)
        if (hybridDetector.initialize()) {
            Log.d(TAG, "HybridDetector initialized")
            modelLabel = when (hybridDetector.getDetectorType()) {
                HybridDetector.DetectorType.TFLITE -> "Model - TFLite"
                else -> "Model - ONNX"
            }
            val dummyBitmap = createBitmap(640, 640)
            hybridDetector.detect(dummyBitmap)
        } else {
            Log.e(TAG, "HybridDetector init failed")
        }
        combinedQualityAssessor = CombinedQualityAssessor(hybridDetector)
        
        // Initialize sound system
        initializeSoundSystem()

        setContent {
            CaptureComposeScreen(
                onPreviewReady = { previewView, w, h ->
                    previewViewSize = IntSize(w, h)
                    startCameraX(previewView)
                    previewViewRef = previewView
                },
                onCaptureRequested = { manualCaptureTrigger() },
                onTorchToggle = { enable -> 
                    Log.d(TAG, "Torch toggle requested: $enable")
                    try {
                        if (camera != null) {
                            camera?.cameraControl?.enableTorch(enable)
                            Log.d(TAG, "Torch set to: $enable")
                        } else {
                            Log.w(TAG, "Camera not ready for torch toggle")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to toggle torch: ${e.message}")
                    }
                },
                updateOverlayRect = { rect -> lastOverlayRectF = rect },
                qualityText = qualityText,
                showCaptureIndicatorAutoCapture = showCaptureIndicatorState.value,
                isCapturing = isCapturing,
                isCountdownActive = isCountdownActive,
                countdownValue = countdownValue,
                captureStatus = captureStatus,
                progressStep = progressStep
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraExecutor.shutdown()
        } catch (ignored: Exception) {
        }
        cameraProvider?.unbindAll()
        try {
            hybridDetector.close()
        } catch (ignored: Exception) {
        }
        // Cleanup sound system
        releaseSoundSystem()
    }
    
    private fun initializeSoundSystem() {
        try {
            // Try to load hold.mp3 (optional)
            try {
                val afd = assets.openFd("hold.mp3")
                holdSoundPlayer = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    prepare()
                    isLooping = false
                }
                Log.d(TAG, "Hold sound loaded successfully")
            } catch (e: Exception) {
                Log.w(TAG, "hold.mp3 not found - skipping")
                holdSoundPlayer = null
            }
            
            // Load captured.mp3 (required)
            val afd2 = assets.openFd("captured.mp3")
            capturedSoundPlayer = MediaPlayer().apply {
                setDataSource(afd2.fileDescriptor, afd2.startOffset, afd2.length)
                afd2.close()
                prepare()
                isLooping = false
            }
            Log.d(TAG, "Captured sound loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load captured.mp3: ${e.message}")
            // Fallback to system beep
            capturedSoundPlayer = null
        }
    }
    
    private fun releaseSoundSystem() {
        try {
            holdSoundPlayer?.release()
            capturedSoundPlayer?.release()
            holdSoundPlayer = null
            capturedSoundPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release sound system: ${e.message}")
        }
    }
    
    private fun playHoldSound() {
        try {
            holdSoundPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.prepare()
                player.start()
                Log.d(TAG, "Hold sound played successfully")
            } ?: run {
                // Fallback: Use system beep if no sound file
                Log.w(TAG, "No hold sound file - using system beep")
                playSystemBeep()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play hold sound: ${e.message}")
            // Fallback: Use system beep
            playSystemBeep()
        }
    }
    
    private fun playCapturedSound() {
        try {
            capturedSoundPlayer?.let { player ->
                // Stop any current playback
                if (player.isPlaying) {
                    player.stop()
                }
                // Reset to beginning and play
                player.seekTo(0)
                player.start()
                Log.d(TAG, "🎵 captured.mp3 played successfully")
                // Show visual feedback
                runOnUiThread {
                    Toast.makeText(this, "🔊 Capture Sound Played!", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                // Fallback: Use system beep if no sound file
                Log.w(TAG, "No capture sound file - using system beep")
                playSystemBeep()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play captured sound: ${e.message}")
            // Fallback: Use system beep
            playSystemBeep()
        }
    }
    
    private fun playSystemBeep() {
        try {
            // Use system beep as fallback
            val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
            Log.d(TAG, "System beep played as capture sound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play system beep: ${e.message}")
        }
    }
    
    private fun updateCaptureStatus(status: String) {
        captureStatus = status
        Log.d(TAG, "Capture Status: $status")
    }
    
    private fun updateProgressStep(step: Int) {
        progressStep = step
        Log.d(TAG, "Progress Step: $step")
    }
    
    private fun captureImmediately(
        previewView: PreviewView,
        viewOverlay: RectF,
        mappedOverlay: RectF,
        rotationDeg: Int
    ) {
        if (isCapturing || hasCaptured) return
        
        Log.d(TAG, "Starting immediate capture")
        updateProgressStep(2) // Capturing step
        updateCaptureStatus("Capturing... Hold still!")
        
        // Request focus and capture
        requestFocusOnOverlay(previewView, viewOverlay) {
            Handler(Looper.getMainLooper()).postDelayed({
                // Update UI to show capturing
                updateCaptureStatus("📸 CAPTURED! Processing...")
                captureWithCameraX(previewView, viewOverlay)
                showCaptureIndicatorState.value = true
            }, 250)
        }
    }

    private fun startCameraX(previewView: PreviewView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(previewView)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
    private fun bindUseCases(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        // Calculate optimal resolution based on preview view size
        // This ensures captured image matches exactly what user sees in preview
        val previewViewWidth = previewViewSize.width
        val previewViewHeight = previewViewSize.height
        
        // Use preview view dimensions if available, otherwise fallback to constants
        val optimalWidth = if (previewViewWidth > 0) previewViewWidth else CAPTURE_WIDTH
        val optimalHeight = if (previewViewHeight > 0) previewViewHeight else CAPTURE_HEIGHT
        
        // Log resolutions for debugging
        Log.d(TAG, "Preview view size: ${previewViewWidth}x${previewViewHeight}")
        Log.d(TAG, "Optimal resolution: ${optimalWidth}x${optimalHeight}")
        Log.d(TAG, "Fallback resolution: ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}")

        // Preview - use optimal resolution for exact match with preview
        val preview = Preview.Builder()
            .setDefaultResolution(android.util.Size(optimalWidth, optimalHeight))
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        // ImageAnalysis (single instance - stored in property)
        imageAnalyzer = ImageAnalysis.Builder()
            .setDefaultResolution(android.util.Size(optimalWidth, optimalHeight))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()


        // --- IMPORTANT: use explicit Analyzer object to avoid Live Edit / proxy issues ---
        imageAnalyzer.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
            override fun analyze(imageProxy: ImageProxy) {
                Log.d(
                    TAG,
                    "Analyzer ENTER - format=${imageProxy.format} rotation=${imageProxy.imageInfo.rotationDegrees} size=${imageProxy.width}x${imageProxy.height}"
                )
                try {
                    if (!isAutoCaptureMode || isCapturing || hasCaptured) {
                        imageProxy.close()
                        Log.d(
                            TAG,
                            "Analyzer SKIP - isAutoCaptureMode=$isAutoCaptureMode isCapturing=$isCapturing hasCaptured=$hasCaptured"
                        )
                        return
                    }

                    val bitmap = imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        // run quality assessment quickly (synchronous on this thread)
                        val assessment = combinedQualityAssessor?.assessQuality(bitmap)
                        if (assessment != null) {
                            runOnUiThread { updateQualityDisplay(assessment) }
                        }

                        val overlay = lastOverlayRectF

                        if (assessment != null && overlay != null) {
                            val aiConfidence = (assessment.aiConfidence * 100).toInt()
                            Log.d(
                                TAG,
                                "Assessment: ai=${assessment.aiConfidence} ai%=$aiConfidence fingers=${assessment.fingerCount} isQuality=${assessment.isQuality}"
                            )

                            // map overlay to bitmap coords using current bitmap + rotation
                            val rotationDeg = imageProxy.imageInfo.rotationDegrees
                            val mappedOverlay = try {
                                mapOverlayToBitmapCoords(
                                    overlay,
                                    previewViewSize,
                                    bitmap.width,
                                    bitmap.height,
                                    rotationDeg
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "mapOverlayToBitmapCoords failed: ${e.message}", e)
                                RectF(overlay)
                            }

                            val passedQuality = validateQualityForCapture(assessment)

                            val shouldDetector = try {
                                hybridDetector.shouldAutoCapture(mappedOverlay)
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "HybridDetector.shouldAutoCapture threw: ${e.message}",
                                    e
                                )
                                false
                            }

                            val stabilityCheck = try {
                                checkStability(overlay) // stability is view-space history — keep this
                            } catch (e: Exception) {
                                Log.w(TAG, "checkStability threw: ${e.message}", e)
                                false
                            }

                            val stabilityOk = shouldDetector && stabilityCheck

                            Log.d(
                                TAG,
                                "AutoCheck detailed -> passedQuality=$passedQuality shouldDetector=$shouldDetector stabilityCheck=$stabilityCheck stabilityOk=$stabilityOk aiConfidence=$aiConfidence isQuality=${assessment.isQuality} fingerCount=${assessment.fingerCount} mappedOverlay=$mappedOverlay previewSize=${previewViewSize.width}x${previewViewSize.height}"
                            )

                            // require consecutive ready frames to avoid single-frame misses
                            if (passedQuality && shouldDetector && stabilityCheck && aiConfidence > 80 && assessment.isQuality) {
                                readyFrameCount++
                                Log.d(
                                    TAG,
                                    "readyFrameCount=$readyFrameCount / $REQUIRED_READY_FRAMES_FOR_COUNTDOWN"
                                )
                            } else {
                                if (readyFrameCount > 0) Log.d(
                                    TAG,
                                    "readyFrame reset (conditions failed)"
                                )
                                readyFrameCount = 0
                            }

                            // capture immediately after N consecutive ready frames
                            if (readyFrameCount >= REQUIRED_READY_FRAMES_FOR_COUNTDOWN) {
                                readyFrameCount = 0
                                Log.d(TAG, "CONDITIONS PASSED (consecutive) - capturing immediately")
                                runOnUiThread {
                                    // Update UI to show capturing
                                    updateCaptureStatus("Capturing... Hold still!")
                                    // Capture immediately without countdown
                                    captureImmediately(
                                        previewView,
                                        overlay,
                                        mappedOverlay,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                }
                            } else {
                                addToHistory(overlay)

                            }
                        }

                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analyzer error: ${e.message}", e)
                } finally {
                    // ALWAYS close imageProxy exactly once here
                    imageProxy.close()
                }
            }
        })

        // ImageCapture with Camera2Interop options - optimized for fingerprint capture
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setDefaultResolution(android.util.Size(optimalWidth, optimalHeight))
            .setTargetRotation(rotation)

        val ext = Camera2Interop.Extender(imageCaptureBuilder)
        
        // High quality settings for fingerprint capture
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.JPEG_QUALITY,
            100.toByte()
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.EDGE_MODE,
            android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE,
            android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        )
        
        // Optimized exposure settings for close-up fingerprint capture
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            -2  // No exposure compensation for natural lighting
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO
        )
        
        // Focus settings for close-up capture
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        
        // Additional quality settings for fingerprint capture
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK,
            false
        )
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK,
            false
        )
        
        // Flash settings - reduce torch brightness to prevent shine
        // Don't override FLASH_MODE to allow torch control
        
        // Reduce overall exposure to prevent shine with torch
        ext.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            -1  // Reduce exposure slightly since torch is always on
        )

        imageCapture = imageCaptureBuilder.build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture
            )
            // Enable torch by default for fingerprint capture with controlled brightness
            try {
                camera?.cameraControl?.enableTorch(true)
                Log.d(TAG, "CameraX bound successfully with torch enabled at reduced brightness")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable torch on camera bind: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed: ${e.message}", e)
        }
    }

    private fun mapOverlayToBitmapCoords(
        overlay: RectF,
        previewSize: IntSize,
        bmpWidth: Int,
        bmpHeight: Int,
        rotationDegrees: Int
    ): RectF {
        val viewW = previewSize.width.toFloat()
        val viewH = previewSize.height.toFloat()
        if (viewW == 0f || viewH == 0f) return RectF(overlay)

        val scaleX = bmpWidth.toFloat() / viewW
        val scaleY = bmpHeight.toFloat() / viewH

        val left = overlay.left * scaleX
        val top = overlay.top * scaleY
        val right = overlay.right * scaleX
        val bottom = overlay.bottom * scaleY
        val rect = RectF(left, top, right, bottom)

        // normalize rotation to 0/90/180/270
        return when ((rotationDegrees % 360 + 360) % 360) {
            0 -> rect
            90 -> RectF(top, (bmpWidth - right), bottom, (bmpWidth - left))
            180 -> RectF(
                (bmpWidth - right),
                (bmpHeight - bottom),
                (bmpWidth - left),
                (bmpHeight - top)
            )

            270 -> RectF((bmpHeight - bottom), left, (bmpHeight - top), right)
            else -> rect
        }
    }


    private fun addToHistory(rect: RectF) {
        synchronized(detectionHistory) {
            if (detectionHistory.size >= STABILITY_HISTORY) {
                detectionHistory.removeFirst()
            }
            detectionHistory.addLast(RectF(rect))
        }
    }

    private fun clearHistory() {
        synchronized(detectionHistory) { detectionHistory.clear() }
    }

    private fun checkStability(newRect: RectF): Boolean {
        addToHistory(newRect)
        synchronized(detectionHistory) {
            if (detectionHistory.size < STABILITY_HISTORY) return false
            val first = detectionHistory.first
            // IoU between first and last
            val last = detectionHistory.last
            val iou = computeIoU(first, last)
            // size variation
            val sizes = detectionHistory.map { it.width() * it.height() }
            val minS = sizes.minOrNull() ?: 0f
            val maxS = sizes.maxOrNull() ?: 1f
            val sizeRatio = if (minS == 0f) 1f else maxS / minS
            val sizeOk = sizeRatio <= (1f + STABILITY_SIZE_TOLERANCE)
            Log.d(TAG, "Stability IoU=$iou sizeRatio=$sizeRatio sizeOk=$sizeOk")
            return iou >= STABILITY_IOU_THRESHOLD && sizeOk
        }
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val interW = max(0f, right - left)
        val interH = max(0f, bottom - top)
        val inter = interW * interH
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun manualCaptureTrigger() {
        if (isCapturing) return
        val overlay = lastOverlayRectF ?: run {
            Toast.makeText(this, "Position overlay first", Toast.LENGTH_SHORT).show()
            return
        }

        if (lastAssessment != null) {
            val passedQuality = validateQualityForCapture(lastAssessment!!)
            val aiConfidence = (lastAssessment!!.aiConfidence * 100).toInt()
            if (lastAssessment!!.fingerCount == 0) {
                Toast.makeText(this, "No fingers", Toast.LENGTH_SHORT).show()
                return
            } else if (lastAssessment!!.fingerCount < 4) {
                Toast.makeText(this, "Not enough fingers", Toast.LENGTH_SHORT).show()
                return
            } else if (!passedQuality || aiConfidence <= 80 || !lastAssessment!!.isQuality) {
                Toast.makeText(this, "Quality too low for capture", Toast.LENGTH_SHORT).show()
                return
            } else {
                isCapturing = true
                captureWithCameraX(null, overlay)
            }
        } else {
            Toast.makeText(this, "No assessment yet", Toast.LENGTH_SHORT).show()
        }

    }

    private fun requestFocusOnOverlay(
        previewView: PreviewView,
        overlay: RectF,
        onComplete: () -> Unit
    ) {
        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(overlay.centerX(), overlay.centerY())
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            val future = camera?.cameraControl?.startFocusAndMetering(action)
            // When focus completes, invoke onComplete (best-effort)
            future?.addListener({
                onComplete()
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.w(TAG, "Focus request failed: ${e.message}")
            onComplete()
        }
    }

    private fun captureWithCameraX(previewView: PreviewView?, overlay: RectF?) {
        val ic = imageCapture ?: run {
            Log.w(TAG, "ImageCapture not ready")
            isCapturing = false
            return
        }


        // If overlay provided, store a local copy for cropping
        val overlayLocal = overlay?.let { RectF(it) }
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    // Process capture on IO
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap == null) {
                                throw Exception("Failed to convert capture to bitmap")
                            }
                            
                            // Log actual captured image dimensions
                            Log.d(TAG, "Captured image dimensions: ${bitmap.width}x${bitmap.height}")
                            Log.d(TAG, "Preview view size: ${previewViewSize.width}x${previewViewSize.height}")

                            // Compute blur metrics (use scaled copy like Camera2)
                            val maxSide = 800
                            val scaled = if (bitmap.width > maxSide || bitmap.height > maxSide) {
                                val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                                bitmap.scale(
                                    (bitmap.width * scale).toInt(),
                                    (bitmap.height * scale).toInt()
                                )
                            } else {
                                bitmap
                            }

                            val lapVar = try {
                                computeLaplacianVariance(scaled)
                            } catch (e: Exception) {
                                Double.NaN
                            }
                            val ten = try {
                                computeTenengradScore(scaled)
                            } catch (e: Exception) {
                                Double.NaN
                            }

                            if (scaled !== bitmap) scaled.recycle()

                            Log.d(TAG, "Captured blur metrics lapVar=$lapVar tenengrad=$ten")

                            if (lapVar.isFinite() && lapVar < LAP_VAR_REJECT) {
                                // immediate retake with better user feedback
                                Log.w(TAG, "Captured image too blurry (lapVar $lapVar) -> retake")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@CaptureFingerActivity,
                                        "Image too blurry — Please hold still and retaking...",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    isCapturing = false
                                    // Reset countdown and detection state for retry
                                    readyFrameCount = 0
                                    clearHistory()
                                }
                                imageProxy.close()
                                return@launch
                            }

                            val lapNorm =
                                if (lapVar.isFinite()) ((lapVar - LAP_VAR_REJECT) / (LAP_VAR_PASS - LAP_VAR_REJECT)).coerceIn(
                                    0.0,
                                    1.0
                                ) else 0.0
                            val tenNorm =
                                if (ten.isFinite()) (ten / TENENGRAD_EXPECTED_MAX).coerceIn(
                                    0.0,
                                    1.0
                                ) else 0.0
                            val combinedScore = 0.7 * lapNorm + 0.3 * tenNorm
                            Log.d(
                                TAG,
                                "Normalized metrics lapNorm=$lapNorm tenNorm=$tenNorm combined=$combinedScore"
                            )

                            if (combinedScore < COMBINED_ACCEPT_THRESHOLD) {
                                consecutiveClearFrames = 0
                                Log.w(TAG, "Combined below threshold ($combinedScore) -> retake")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@CaptureFingerActivity,
                                        "Poor image quality - Please ensure good lighting and retaking...",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    isCapturing = false
                                    // Reset countdown and detection state for retry
                                    readyFrameCount = 0
                                    clearHistory()
                                }
                                imageProxy.close()
                                return@launch
                            }

                            consecutiveClearFrames++
                            if (consecutiveClearFrames < REQUIRED_CONSECUTIVE_CLEAR_FRAMES) {
                                Log.d(TAG, "Need more clear frames - retake")
                                withContext(Dispatchers.Main) {
                                    isCapturing = false
                                }
                                imageProxy.close()
                                return@launch
                            }
                            consecutiveClearFrames = 0

                            // Crop to exact camera preview frame (not D-shape overlay)
                            Log.d(TAG, "Original captured image size: ${bitmap.width}x${bitmap.height}")
                            Log.d(TAG, "Preview view size: ${previewViewSize.width}x${previewViewSize.height}")
                            
                            val finalBitmap = cropToPreviewFrame(bitmap, previewViewSize)
                            Log.d(TAG, "Preview frame cropped image size: ${finalBitmap.width}x${finalBitmap.height}")

                            // Save temporary file (preview frame cropped)
                            val outFile = File(
                                getExternalFilesDir(null),
                                "fingerprint_preview_${System.currentTimeMillis()}.jpg"
                            )
                            FileOutputStream(outFile).use { fos ->
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                            }
                            Log.d(TAG, "Saved preview frame cropped image: ${outFile.absolutePath}")

                            // Process with FingerprintProcessor (OpenCV) - balanced sharpening without whitening
                            val processingOptions = FingerprintProcessor.ProcessingOptions(
                                enableMetrics = true,
                                enableEnhancement = true,
                                claheClipLimit = 1.2,   // Gentle contrast to avoid whitening
                                claheTileGrid = 8,       // Standard tiles
                                unsharpSigma = 1.0,      // Balanced sharpening
                                unsharpAmount = 0.3      // Moderate sharpening without over-brightening
                            )
                            val processingResult = try {
                                FingerprintProcessor.processFingerprint(finalBitmap, null, processingOptions)
                            } catch (e: Exception) {
                                Log.w(TAG, "Fingerprint processing error: ${e.message}")
                                null
                            }
                            Log.d(TAG, "Fingerprint processing result: $processingResult")

                            if (processingResult == null || !processingResult.isAcceptable) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@CaptureFingerActivity,
                                        "Processing failed - Retake",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    isCapturing = false
                                }
                                imageProxy.close()
                                return@launch
                            }


                            // Save the sharpened/enhanced image for better fingerprint visibility
                            val enhancedBitmap = processingResult.processedBitmap
                            val enhancedFile = File(
                                getExternalFilesDir(null),
                                "fingerprint_sharpened_${System.currentTimeMillis()}.jpg"
                            )
                            FileOutputStream(enhancedFile).use { fos ->
                                enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                            }
                            Log.d(TAG, "Saved sharpened image: ${enhancedFile.absolutePath}")

                            // Skip manual cropping - go directly to preview with SHARPENED image
                            withContext(Dispatchers.Main) {
                                updateProgressStep(3) // Processing step
                                updateCaptureStatus("✅ Image captured and sharpened!")
                                
                                // Play capture sound only when image is successfully processed and ready
                                playCapturedSound()
                                
                                countdownJob?.cancel()
                                isCountdownActive = false
                                isCapturing = false
                                hasCaptured = false
                                readyFrameCount = 0
                                clearHistory()
                                val intent = Intent(
                                    this@CaptureFingerActivity,
                                    PreviewActivity::class.java
                                ).apply {
                                    putExtra(
                                        PreviewActivity.EXTRA_IMAGE_PATH,
                                        enhancedFile.absolutePath
                                    )
                                    putExtra(
                                        PreviewActivity.EXTRA_USER_ID,
                                        intent?.getStringExtra(CaptureActivity.EXTRA_USER_ID) ?: ""
                                    )
                                    putExtra(
                                        PreviewActivity.EXTRA_MODE,
                                        intent?.getStringExtra(CaptureActivity.EXTRA_MODE)
                                            ?: "register"
                                    )
                                }
                                startActivity(intent)
                                hasCaptured = true
                                isCapturing = false
                            }

                            if (finalBitmap !== bitmap) finalBitmap.recycle()
                            bitmap.recycle()
                        } catch (e: Exception) {
                            Log.e(TAG, "Capture processing failed: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@CaptureFingerActivity,
                                    "Capture error: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isCapturing = false
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture error: ${exception.message}", exception)
                    isCapturing = false
                }
            })
    }

    private fun updateQualityDisplay(assessment: CombinedQualityAssessor.QualityAssessment) {
        lastAssessment = assessment
        val aiConfidence = (assessment.aiConfidence * 100).toInt()
        val status = if (assessment.isQuality) "READY" else "NEEDS IMPROVEMENT"
        val statusColor = if (assessment.isQuality) "🟢" else "🔴"
        
        // Update progress step based on detection quality
        if (assessment.fingerCount > 0 && aiConfidence > 50) {
            updateProgressStep(1) // Detecting step
            updateCaptureStatus("Detecting fingers... ${assessment.fingerCount} found - Keep centered!")
        } else {
            updateProgressStep(0) // Positioning step
            updateCaptureStatus("Center 4 fingers on the yellow crosshairs")
        }
        
        qualityText = """
            $modelLabel
            AI Confidence: ${aiConfidence}%
            Fingers Detected: ${assessment.fingerCount}
            Status: $statusColor $status
            ${assessment.recommendation}
        """.trimIndent()
    }

    private fun validateQualityForCapture(assessment: CombinedQualityAssessor.QualityAssessment): Boolean {
        if (assessment.fingerCount < 3 || assessment.fingerCount > 4) return false
        if (assessment.aiConfidence < 0.25f) return false
        if (!assessment.isQuality) return false
        return true
    }

    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val format = imageProxy.format
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        try {
            if (format == ImageFormat.JPEG) {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                if (rotationDegrees != 0) {
                    bmp = rotateBitmap(bmp, rotationDegrees)
                }
                return bmp
            }
            if (format == ImageFormat.YUV_420_888) {
                val img = imageProxy.image ?: return null
                val nv21 = yuv420ToNv21(img)
                val yuv =
                    YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                val baos = ByteArrayOutputStream()
                val ok =
                    yuv.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, baos)
                if (!ok) return null
                var bmp =
                    BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size()) ?: return null
                if (rotationDegrees != 0) {
                    bmp = rotateBitmap(bmp, rotationDegrees)
                }
                return bmp
            }
            // fallback: try read first plane bytes
            if (imageProxy.planes.isNotEmpty()) {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                if (rotationDegrees != 0) bmp = rotateBitmap(bmp, rotationDegrees)
                return bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageProxyToBitmap error: ${e.message}", e)
        }
        return null
    }

    private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        bmp.recycle()
        return rotated
    }

    /**
     * Convert YUV_420_888 -> NV21
     */
    /**
     * Convert android.media.Image (YUV_420_888) to NV21 byte array robustly.
     * This uses absolute indexing and bounds checks to avoid BufferUnderflowException.
     */
    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        // --- Y plane ---
        try {
            for (row in 0 until height) {
                val yRowStart = row * yRowStride
                for (col in 0 until width) {
                    val yIndex = yRowStart + col * yPixelStride
                    nv21[pos++] =
                        if (yIndex >= 0 && yIndex < yBuffer.limit()) yBuffer.get(yIndex) else 0
                }
            }

            // --- UV planes (NV21 expects V then U interleaved) ---
            val chromaHeight = height / 2
            val chromaWidth = width / 2

            var uvPos = width * height
            for (row in 0 until chromaHeight) {
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until chromaWidth) {
                    // indexes into U and V planes
                    val uIndex = uRowStart + col * uPixelStride
                    val vIndex = vRowStart + col * vPixelStride

                    // NV21 expects V first, then U
                    val vByte =
                        if (vIndex >= 0 && vIndex < vBuffer.limit()) vBuffer.get(vIndex) else 0
                    val uByte =
                        if (uIndex >= 0 && uIndex < uBuffer.limit()) uBuffer.get(uIndex) else 0

                    if (uvPos < nv21.size) nv21[uvPos++] = vByte
                    if (uvPos < nv21.size) nv21[uvPos++] = uByte
                }
            }
        } catch (e: Exception) {
            // Defensive: log and return a zeroed NV21 to avoid crashing the analyzer
            Log.w(TAG, "yuv420ToNv21 conversion failed: ${e.message}")
            return ByteArray(width * height * 3 / 2)
        }

        return nv21
    }

    // --- OpenCV metrics copied from Camera2 code ---

    @Throws(Exception::class)
    private fun computeLaplacianVariance(bitmap: Bitmap): Double {
        val mat = Mat()
        org.opencv.android.Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val stdDev = std.toArray()[0]
        val lapVar = stdDev * stdDev
        listOf(mat, gray, lap, mean, std).forEach { it.release() }
        return lapVar
    }

    @Throws(Exception::class)
    private fun computeTenengradScore(bitmap: Bitmap): Double {
        val mat = Mat()
        org.opencv.android.Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_64F, 1, 0, 3)
        Imgproc.Sobel(gray, gy, CvType.CV_64F, 0, 1, 3)
        val mag = Mat()
        Core.magnitude(gx, gy, mag)
        val sq = Mat()
        Core.multiply(mag, mag, sq)
        val mean = Core.mean(sq).`val`[0]
        listOf(mat, gray, gx, gy, mag, sq).forEach { it.release() }
        return mean
    }

    // Crop to exact camera preview frame to match what user sees on screen
    private fun cropToPreviewFrame(bitmap: Bitmap, previewSize: IntSize): Bitmap {
        try {
            Log.d(TAG, "Cropping to preview frame: ${previewSize.width}x${previewSize.height}")
            
            // Calculate the aspect ratio of the captured image vs preview
            val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
            
            Log.d(TAG, "Image aspect ratio: ${String.format("%.2f", imageAspectRatio)}")
            Log.d(TAG, "Preview aspect ratio: ${String.format("%.2f", previewAspectRatio)}")
            
            // Calculate crop dimensions to match preview frame exactly
            val cropWidth: Int
            val cropHeight: Int
            val offsetX: Int
            val offsetY: Int
            
            if (imageAspectRatio > previewAspectRatio) {
                // Image is wider than preview - crop width to match preview aspect ratio
                cropHeight = bitmap.height
                cropWidth = (bitmap.height * previewAspectRatio).toInt()
                offsetX = (bitmap.width - cropWidth) / 2
                offsetY = 0
                Log.d(TAG, "Image wider than preview - cropping width from ${bitmap.width} to ${cropWidth}")
            } else {
                // Image is taller than preview - crop height to match preview aspect ratio
                cropWidth = bitmap.width
                cropHeight = (bitmap.width / previewAspectRatio).toInt()
                offsetX = 0
                offsetY = (bitmap.height - cropHeight) / 2
                Log.d(TAG, "Image taller than preview - cropping height from ${bitmap.height} to ${cropHeight}")
            }
            
            Log.d(TAG, "Initial crop dimensions: ${cropWidth}x${cropHeight}, offset: (${offsetX}, ${offsetY})")
            
            // Additional vertical cropping to remove extra frame
            // Use dynamic cropping based on image size - larger images need more aggressive cropping
            val verticalCropRatio = when {
                cropHeight > 2000 -> 0.20f // Large images: remove 20% from top and bottom
                cropHeight > 1500 -> 0.18f // Medium images: remove 18% from top and bottom
                cropHeight > 1000 -> 0.15f // Small images: remove 15% from top and bottom
                else -> 0.12f // Very small images: remove 12% from top and bottom
            }
            
            val verticalCropPixels = (cropHeight * verticalCropRatio).toInt()
            val finalCropHeight = cropHeight - (verticalCropPixels * 2) // Remove from both top and bottom
            val finalOffsetY = offsetY + verticalCropPixels // Adjust offset to center the crop
            
            Log.d(TAG, "Additional vertical cropping: removing ${verticalCropPixels}px from top and bottom (${String.format("%.1f", verticalCropRatio * 100)}% ratio)")
            Log.d(TAG, "Final crop dimensions: ${cropWidth}x${finalCropHeight}, offset: (${offsetX}, ${finalOffsetY})")
            
            // Ensure crop dimensions are within image bounds
            val finalCropWidth = cropWidth.coerceAtMost(bitmap.width - offsetX)
            val finalCropHeightBounded = finalCropHeight.coerceAtMost(bitmap.height - finalOffsetY)
            
            if (finalCropWidth <= 0 || finalCropHeightBounded <= 0) {
                Log.w(TAG, "Invalid crop dimensions after vertical adjustment, using original crop")
                val croppedBitmap = Bitmap.createBitmap(bitmap, offsetX, offsetY, cropWidth, cropHeight)
                return croppedBitmap
            }
            
            val croppedBitmap = Bitmap.createBitmap(bitmap, offsetX, finalOffsetY, finalCropWidth, finalCropHeightBounded)
            Log.d(TAG, "Preview frame cropping successful: ${croppedBitmap.width}x${croppedBitmap.height}")
            
            // Calculate the reduction percentage
            val widthReduction = ((bitmap.width - croppedBitmap.width).toFloat() / bitmap.width * 100).toInt()
            val heightReduction = ((bitmap.height - croppedBitmap.height).toFloat() / bitmap.height * 100).toInt()
            Log.d(TAG, "Crop reduction: ${widthReduction}% width, ${heightReduction}% height")
            
            return croppedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Preview frame cropping failed: ${e.message}", e)
            return bitmap
        }
    }

    // Crop and mask to overlay path (D-shape). previewViewSize must be the size of PreviewView in pixels.
    private fun cropAndMaskToOverlay(
        bitmap: Bitmap,
        overlayRect: RectF,
        previewSize: IntSize
    ): Bitmap {
        try {
            val viewW = previewSize.width.toFloat()
            val viewH = previewSize.height.toFloat()
            if (viewW == 0f || viewH == 0f) return bitmap
            val scaleX = bitmap.width.toFloat() / viewW
            val scaleY = bitmap.height.toFloat() / viewH

            val cropLeft = (overlayRect.left * scaleX).toInt().coerceAtLeast(0)
            val cropTop = (overlayRect.top * scaleY).toInt().coerceAtLeast(0)
            val cropWidth =
                (overlayRect.width() * scaleX).toInt().coerceAtMost(bitmap.width - cropLeft)
            val cropHeight =
                (overlayRect.height() * scaleY).toInt().coerceAtMost(bitmap.height - cropTop)
            if (cropWidth <= 0 || cropHeight <= 0) return bitmap

            val rectCrop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)

            val masked = createBitmap(cropWidth, cropHeight)
            val canvas = android.graphics.Canvas(masked)

            // Recreate D-shape path in preview coords, then transform to crop coords
            val left = overlayRect.left
            val top = overlayRect.top
            val right = overlayRect.right
            val bottom = overlayRect.bottom
            val roiW = overlayRect.width()
            val roiH = overlayRect.height()
            val radius = roiH / 2f

            val roiPath = Path().apply {
                reset()
                moveTo(left, top)
                lineTo(right - radius, top)
                arcTo(RectF(right - 2 * radius, top, right, bottom), -90f, 180f, false)
                lineTo(left, bottom)
                close()
            }

            // Transform path: translate and scale to crop coords
            val matrix = Matrix().apply {
                postTranslate(-left, -top)
                postScale(cropWidth.toFloat() / roiW, cropHeight.toFloat() / roiH)
            }
            roiPath.transform(matrix)

            canvas.clipPath(roiPath)
            canvas.drawBitmap(rectCrop, 0f, 0f, null)
            rectCrop.recycle()
            return masked
        } catch (e: Exception) {
            Log.w(TAG, "cropAndMaskToOverlay failed: ${e.message}")
            return bitmap
        }
    }


    override fun onPause() {
        super.onPause()
        // stop active countdown / avoid dangling jobs
        countdownJob?.cancel()
        isCountdownActive = false
        showCaptureIndicatorState.value = false
        // optionally pause analyzer - you can unbind to be safe
        try {
            cameraProvider?.unbind(imageAnalyzer)
        } catch (e: Exception) { /* ignore */
        }

        // keep flags conservative
        isCapturing = false
    }

    override fun onResume() {
        super.onResume()
        // restore things and rebind if needed
        isCountdownActive = false
        isCapturing = false
        hasCaptured = false
        showCaptureIndicatorState.value = false
        readyFrameCount = 0
        clearHistory()
        previewViewRef?.let { bindUseCases(it) }
    }

}

// 2.) Show Users Messaeg like capturng if requirment meet show user hand not coming right or so and low time in 3 seconds
// 3.) Cropping Horizontal or Vertical in One Landscape
// 4.) Move Far Move Close or some thing and show please hold on when four fingers so we can show user to hold to capture

//class CaptureFingerActivity : ComponentActivity() {
//
//    private var isAutoCaptureMode = true
//    private var isCapturing = false
//    private var hasCaptured = false
//    private var isMonitoringStopped = false
//
//    private var qualityText: String? = null
//    private var modelLabel: String = ""
//
//    private lateinit var imageAnalyzer: ImageAnalysis
//
//    private val autoCaptureScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
//
//    companion object {
//        private const val TAG = "CaptureComposeActivity"
//        private const val CAPTURE_WIDTH = 1280
//        private const val CAPTURE_HEIGHT = 720
//
//        const val EXTRA_MODE = "capture_mode"
//        const val EXTRA_USER_ID = "user_id"
//
//    }
//
//    var uid: String?=null
//    var mode: String?=null
//
//    // CameraX elements
//    private var cameraProvider: ProcessCameraProvider? = null
//    private var imageCapture: ImageCapture? = null
//    private var camera: Camera? = null
//    private val cameraExecutor = Executors.newSingleThreadExecutor()
//
//    // Overlay mapping: last overlay rect in preview coords (pixels)
//    private var lastOverlayRectF: RectF? = null
//
//    // Preview view size in pixels (updated from Compose)
//    private var previewViewSize: IntSize = IntSize(0, 0)
//
//    // Helpers (assumed provided by your project)
//    private lateinit var hybridDetector: HybridDetector
//    private var combinedQualityAssessor: CombinedQualityAssessor? = null
//
//    private val requestPermissionLauncher =
//        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
//            if (!granted) {
//                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
//                finish()
//            }
//        }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        // Initialize detectors/assessor (same as your old code)
//        hybridDetector = HybridDetector(this)
//        if (hybridDetector.initialize()) {
//            Log.d(CaptureActivity.Companion.TAG, "Hybrid detector initialized successfully")
//            modelLabel = when (hybridDetector.getDetectorType()) {
//                HybridDetector.DetectorType.TFLITE -> "Model - TFLite"
//                else -> "Model - ONNX"
//            }
//            val dummyBitmap = createBitmap(640, 640)
//            hybridDetector.detect(dummyBitmap)
//            Log.d(TAG, "HybridDetector initialized")
//        } else {
//            Log.e(TAG, "HybridDetector failed init")
//        }
//        combinedQualityAssessor = CombinedQualityAssessor(hybridDetector)
//
//         uid = intent.getStringExtra(CaptureActivity.EXTRA_USER_ID) ?: ""
//         mode = intent?.getStringExtra(CaptureActivity.EXTRA_MODE) ?: "register"
//
//        setContent {
//            CaptureComposeScreen(
//                onPreviewReady = { previewView, w, h ->
//                    previewViewSize = IntSize(w, h)
//                    startCameraX(previewView)
//                },
//                onCaptureRequested = { captureWithCameraX() },
//                onTorchToggle = {},
////                onTorchToggle = { enable -> camera?.cameraControl?.enableTorch(enable) },
//                updateOverlayRect = { rect -> lastOverlayRectF = rect },
//                qualityText = qualityText ?: ""
//
//            )
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        cameraExecutor.shutdown()
//        cameraProvider?.unbindAll()
//        try {
//            hybridDetector.close()
//        } catch (ignored: Exception) {
//            ignored.printStackTrace()
//        }
//    }
//
//    private fun startCameraX(previewView: PreviewView) {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
//            != PackageManager.PERMISSION_GRANTED
//        ) {
//            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
//            return
//        }
//
//        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
//        cameraProviderFuture.addListener({
//            cameraProvider = cameraProviderFuture.get()
//            bindUseCases(previewView)
//        }, ContextCompat.getMainExecutor(this))
//
//        startAutoCaptureMonitoring()
//
//    }
//
//    private fun startAutoCaptureMonitoring() {
//        if (isAutoCaptureMode) {
//            isMonitoringStopped = false
//            Log.d(CaptureActivity.Companion.TAG, "Starting auto-capture monitoring")
//            // Nothing else needed here since analyzer already calls performAutoCaptureAsync()
//        }
//    }
//
//
//    private fun performAutoCaptureAsync(imageProxy: ImageProxy) {
//        lifecycleScope.launch(Dispatchers.IO) {
//            val bitmap = imageProxyToBitmap(imageProxy)
//            val qualityAssessment = combinedQualityAssessor?.assessQuality(bitmap)
//            withContext(Dispatchers.Main) {
//                if (qualityAssessment != null && isAutoCaptureMode && !isCapturing && !hasCaptured) {
//                    updateQualityDisplay(qualityAssessment)
//                    if (validateQualityForCapture(qualityAssessment) && hybridDetector.shouldAutoCapture(
//                            lastOverlayRectF!!
//                        )
//                    ) {
//                        isCapturing = true
//                        captureWithCameraX()
//                    }
//                }
//            }
//            bitmap.recycle()
//            imageProxy.close()
//        }
//    }
//
//    @SuppressLint("RestrictedApi")
//    private fun bindUseCases(previewView: PreviewView) {
//        val provider = cameraProvider ?: return
//        provider.unbindAll()
//
//        val rotation = previewView.display?.rotation ?: 0
//
//        // Preview
//        val preview = Preview.Builder()
//            .setTargetRotation(Surface.ROTATION_0)
//            .build()
//            .also { it.surfaceProvider = previewView.surfaceProvider }
//
//        // ImageAnalysis (lightweight, keep latest)
//        val imageAnalyzer = ImageAnalysis.Builder()
//            .setDefaultResolution(android.util.Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
//            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//            .build()
//            .also { analyzer ->
//                analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
//                    // Optionally: feed frames to your hybridDetector/quality assessor
//                    try {
//                        imageAnalyzer = ImageAnalysis.Builder()
//                            .setDefaultResolution(android.util.Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
//                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//                            .build()
//                            .also { analyzer ->
//                                analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
//                                    performAutoCaptureAsync(imageProxy)
//                                }
//                            }
//                    } finally {
//                        imageProxy.close()
//                    }
//                }
//            }
//
//        // ImageCapture
//        imageCapture = ImageCapture.Builder()
//            .setTargetRotation(Surface.ROTATION_0)
//            .setDefaultResolution(android.util.Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
//            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
//            .build()
//
//        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
//
//        try {
//            camera = provider.bindToLifecycle(
//                this,
//                cameraSelector,
//                preview,
//                imageAnalyzer,
//                imageCapture
//            ) as Camera?
//            Log.d(TAG, "CameraX bound successfully")
//        } catch (e: Exception) {
//            Log.e(TAG, "Bind failed: ${e.message}", e)
//        }
//    }
//
//    private fun captureWithCameraX() {
//        val ic = imageCapture ?: run {
//            Log.w(TAG, "ImageCapture not ready")
//            return
//        }
//
//        ic.takePicture(
//            ContextCompat.getMainExecutor(this),
//            object : ImageCapture.OnImageCapturedCallback() {
//                override fun onCaptureSuccess(imageProxy: ImageProxy) {
//                    lifecycleScope.launch(Dispatchers.IO) {
//                        try {
//                            val bitmap = imageProxyToBitmap(imageProxy)
//                            // Map overlay rect from preview coords -> image coords before cropping
//                            val overlay = lastOverlayRectF
//                            val previewW = previewViewSize.width
//                            val previewH = previewViewSize.height
//
//                            val croppedBitmap =
//                                if (overlay != null && previewW > 0 && previewH > 0) {
//                                    // Map overlay coords to image pixel coords
//                                    val scaleX = bitmap.width.toFloat() / previewW.toFloat()
//                                    val scaleY = bitmap.height.toFloat() / previewH.toFloat()
//
//                                    val left = (overlay.left * scaleX).toInt()
//                                        .coerceIn(0, bitmap.width - 1)
//                                    val top = (overlay.top * scaleY).toInt()
//                                        .coerceIn(0, bitmap.height - 1)
//                                    val w = (overlay.width() * scaleX).toInt()
//                                        .coerceAtMost(bitmap.width - left)
//                                    val h = (overlay.height() * scaleY).toInt()
//                                        .coerceAtMost(bitmap.height - top)
//
//                                    if (w <= 0 || h <= 0) {
//                                        bitmap
//                                    } else {
//                                        Bitmap.createBitmap(bitmap, left, top, w, h)
//                                    }
//                                } else {
//                                    bitmap
//                                }
//
//                            // Optional: apply mask (D-shape) on croppedBitmap if you want exact shape
//                            // For now we just save croppedBitmap
//
//                            val outFile = File(
//                                getExternalFilesDir(null),
//                                "fingerprint_cropped_${System.currentTimeMillis()}.jpg"
//                            )
//                            FileOutputStream(outFile).use { fos ->
//                                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
//                            }
//                            Log.d(TAG, "Saved capture: ${outFile.absolutePath}")
//
//                            try {
//                                val processingResult =
//                                    FingerprintProcessor.processFingerprint(croppedBitmap, null)
//                                Log.d(TAG, "Processing result: $processingResult")
//                            } catch (e: Exception) {
//                                Log.w(TAG, "Fingerprint processing failed: ${e.message}")
//                            }
//
//                            launchMain {
//                                val intent = Intent(
//                                    this@CaptureFingerActivity,
//                                    ImageCropperActivity::class.java
//                                ).apply {
//                                    putExtra(
//                                        ImageCropperActivity.EXTRA_IMAGE_PATH,
//                                        outFile.absolutePath
//                                    )
//                                    putExtra(PreviewActivity.EXTRA_USER_ID, uid)
//                                    putExtra(PreviewActivity.EXTRA_MODE, mode)
//
//                                }
//                                startActivity(intent)
//                            }
//
//                            if (croppedBitmap !== bitmap) {
//                                croppedBitmap.recycle()
//                            }
//                            bitmap.recycle()
//                        } catch (e: Exception) {
//                            Log.e(TAG, "Capture processing failed: ${e.message}", e)
//                        } finally {
//                            imageProxy.close()
//                        }
//                    }
//                }
//
//                override fun onError(exception: ImageCaptureException) {
//                    Log.e(TAG, "Capture error: ${exception.message}", exception)
//                }
//            })
//    }
//
//    // Helper: run on main thread from coroutine
//    private fun launchMain(block: suspend () -> Unit) {
//        lifecycleScope.launch(Dispatchers.Main) { block() }
//    }
//
//    private fun updateQualityDisplay(assessment: CombinedQualityAssessor.QualityAssessment) {
//        runOnUiThread {
//            // ✅ Simple quality metrics
//            val aiConfidence = (assessment.aiConfidence * 100).toInt()
//            val fingerCount = assessment.fingerCount
//
//            // ✅ Update UI with simple metrics
//            qualityText = """
//                🧠 $modelLabel
//                🤖 AI Confidence: ${aiConfidence}%
//                🖐️ Finger Count: $fingerCount
//                📱 Status: ${if (assessment.isQuality) "READY" else "NEEDS IMPROVEMENT"}
//                💡 Tip: ${assessment.recommendation}
//            """.trimIndent()
//
//            // ✅ Debug info for logging
//            Log.d(
//                CaptureActivity.Companion.TAG,
//                "Quality Assessment - AI: ${aiConfidence}%, Fingers: ${fingerCount}, Quality: ${assessment.isQuality}"
//            )
//        }
//    }
//
//    private fun validateQualityForCapture(assessment: CombinedQualityAssessor.QualityAssessment): Boolean {
//        // ✅ REQUIRE 3-4 FINGERS for optimal fingerprint capture
//        if (assessment.fingerCount < 3 || assessment.fingerCount > 4) {
//            Log.d(
//                CaptureActivity.Companion.TAG,
//                "Finger Capture validation FAILED: fingerCount=${assessment.fingerCount} (need 3-4)"
//            )
//            return false
//        }
//        if (assessment.aiConfidence < 0.25f) {
//            Log.d(
//                CaptureActivity.Companion.TAG,
//                "Finger Capture validation FAILED: aiConfidence=${(assessment.aiConfidence * 100).toInt()}% (need 25%+)"
//            )
//            return false
//        }
//        if (!assessment.isQuality) {
//            Log.d(CaptureActivity.Companion.TAG, "Finger Capture validation FAILED: isQuality=false")
//            return false
//        }
//
//        Log.d(
//            CaptureActivity.Companion.TAG,
//            "Finger Capture validation PASSED: fingers=${assessment.fingerCount}, confidence=${(assessment.aiConfidence * 100).toInt()}%"
//        )
//        return true
//    }
//
//
//    // Convert ImageProxy (YUV_420_888) -> Bitmap using NV21 intermediate
//
//
//    /**
//     * Convert ImageProxy to Bitmap robustly.
//     * Supports ImageFormat.JPEG and ImageFormat.YUV_420_888.
//     */
//    @OptIn(ExperimentalGetImage::class)
//    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
//        // Prefer to read imageProxy.image (Image) safely
//        val format = imageProxy.format
//
//        // Handle JPEG (single-plane) — often returned by ImageCapture depending on config
//        if (format == ImageFormat.JPEG) {
//            val buffer = imageProxy.planes[0].buffer
//            val bytes = ByteArray(buffer.remaining())
//            buffer.get(bytes)
//            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//                ?: throw IllegalStateException("Failed to decode JPEG bytes to Bitmap")
//        }
//
//        // Handle YUV_420_888 (3-plane)
//        if (format == ImageFormat.YUV_420_888) {
//            val image = imageProxy.image ?: throw IllegalStateException("imageProxy.image is null")
//
//            val width = imageProxy.width
//            val height = imageProxy.height
//
//            // Convert YUV_420_888 -> NV21 byte array (required by YuvImage)
//            val nv21 = yuv420ToNv21(image)
//
//            // Convert nv21 -> JPEG -> Bitmap
//            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
//            val out = ByteArrayOutputStream()
//            val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
//            if (!ok) throw IllegalStateException("YuvImage.compressToJpeg() returned false")
//            val jpegBytes = out.toByteArray()
//            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
//                ?: throw IllegalStateException("Failed to decode YUV->JPEG bytes to Bitmap")
//        }
//
//        // Other formats — fall back to trying single-plane read (rare) or throw
//        if (imageProxy.planes.isNotEmpty()) {
//            val buffer = imageProxy.planes[0].buffer
//            val bytes = ByteArray(buffer.remaining())
//            buffer.get(bytes)
//            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//                ?: throw IllegalStateException("Failed to decode single-plane bytes to Bitmap (format=$format)")
//        }
//
//        throw IllegalStateException("Unsupported ImageProxy format: $format")
//    }
//
//    /**
//     * Convert android.media.Image in YUV_420_888 to NV21 byte array.
//     * Handles arbitrary rowStride and pixelStride.
//     */
//    private fun yuv420ToNv21(image: Image): ByteArray {
//        val width = image.width
//        val height = image.height
//
//        val yPlane = image.planes[0]
//        val uPlane = image.planes[1]
//        val vPlane = image.planes[2]
//
//        val yBuffer = yPlane.buffer
//        val uBuffer = uPlane.buffer
//        val vBuffer = vPlane.buffer
//
//        val yRowStride = yPlane.rowStride
//        val yPixelStride = yPlane.pixelStride
//
//        val uvRowStride = uPlane.rowStride
//        val uvPixelStride = uPlane.pixelStride
//
//        val nv21 = ByteArray(width * height * 3 / 2)
//
//        var pos = 0
//        val yRow = ByteArray(yRowStride)
//        for (row in 0 until height) {
//
//            yBuffer.position(row * yRowStride)
//            yBuffer.get(yRow, 0, yRowStride)
//
//            if (yPixelStride == 1) {
//                System.arraycopy(yRow, 0, nv21, pos, width)
//                pos += width
//            } else {
//                var out = pos
//                var col = 0
//                while (col < width) {
//                    nv21[out++] = yRow[col * yPixelStride]
//                    col++
//                }
//                pos += width
//            }
//        }
//
//
//        val uvHeight = height / 2
//        val uvWidth = width / 2
//
//        val uRow = ByteArray(uvRowStride)
//        val vRow = ByteArray(uvRowStride)
//
//        var uvPos = width * height
//        for (row in 0 until uvHeight) {
//            vBuffer.position(row * uvRowStride)
//            vBuffer.get(vRow, 0, uvRowStride)
//
//            uBuffer.position(row * uvRowStride)
//            uBuffer.get(uRow, 0, uvRowStride)
//
//            var col = 0
//            while (col < width) {
//                val chromaIndex = (col / 2) * uvPixelStride
//                val vByte = vRow.getOrNull(chromaIndex)
//                val uByte = uRow.getOrNull(chromaIndex)
//                nv21[uvPos++] = vByte
//                nv21[uvPos++] = uByte
//                col += 2
//            }
//        }
//        return nv21
//    }
//
//    // safe extension for ByteArray indexing (returns 0 if out-of-bounds)
//    private fun ByteArray.getOrNull(index: Int): Byte = if (index in indices) this[index] else 0
//}
