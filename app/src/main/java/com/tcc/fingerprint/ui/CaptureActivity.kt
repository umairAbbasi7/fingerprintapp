package com.tcc.fingerprint.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.tcc.fingerprint.R
import com.tcc.fingerprint.detection.HybridDetector
import com.tcc.fingerprint.processing.FingerprintProcessor
import com.tcc.fingerprint.quality.CombinedQualityAssessor
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import java.io.File
import androidx.core.graphics.scale
import java.io.FileOutputStream
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.Path


class CaptureActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var torchButton: ImageButton
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var isTorchOn = true  // Start with torch ON
    private var isCapturing = false
    private var isCameraReady = false
    private var hasCaptured = false
    private var previewSize: android.util.Size? = null
    private var isStabilizationEnabled =
        true

    private var imageReader: ImageReader? = null

    private var isAutoCaptureMode = true  // Enable auto-capture by default
    private var autoCaptureHandler = Handler(Looper.getMainLooper())
    private var autoCaptureStartTime = 0L  // Safety timeout tracking
    private var isMonitoringStopped = false  // Real monitoring stop flag
    private val autoCaptureRunnable = object : Runnable {
        override fun run() {
            // Check if monitoring is actually stopped
            if (isMonitoringStopped) {
                Log.d(TAG, "Monitoring actually stopped - not running")
                return
            }

            // Safety timeout to prevent infinite monitoring loop
            val currentTime = System.currentTimeMillis()
            if (autoCaptureStartTime == 0L) {
                autoCaptureStartTime = currentTime
            }

            // Safety timeout: Stop monitoring after 30 seconds to prevent infinite loop
//            if (currentTime - autoCaptureStartTime > 30000) {
//                Log.w(TAG, "Auto-capture monitoring timeout (30s) - stopping to prevent infinite loop")
//                stopAutoCaptureMonitoring()
//                return
//            }

            Log.d(
                TAG,
                "Auto-capture monitoring: mode=$isAutoCaptureMode, capturing=$isCapturing, captured=$hasCaptured"
            )
            if (isAutoCaptureMode && !isCapturing && !hasCaptured) {
                lifecycleScope.launch {
                    performAutoCaptureAsync()
                }
            }
            autoCaptureHandler.postDelayed(
                this,
                150
            ) // Check every 150ms for optimal responsiveness
        }
    }

    private var isCountdownActive = false
    private var countdownValue = 3
    private var countdownHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownValue > 0) {
                runOnUiThread {
                    statusText.text = "Get Ready! ${countdownValue}"
                    overlayView.showCountdown(countdownValue)
                }
                countdownValue--
                countdownHandler.postDelayed(this, 1000)
            } else {
                runOnUiThread {
                    statusText.text = "CAPTURING!"
                    overlayView.hideCountdown()
                    captureImage()
                }
                isCountdownActive = false
            }
        }
    }

    private lateinit var hybridDetector: HybridDetector
    private lateinit var qualityText: TextView
    private var modelLabel: String = ""
    private var combinedQualityAssessor: CombinedQualityAssessor? = null

    companion object {
        const val EXTRA_MODE = "capture_mode"
        const val EXTRA_USER_ID = "user_id"
        private const val CAMERA_PERMISSION_REQUEST = 100
        const val TAG = "CaptureActivity"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720

        private var consecutiveClearFrames = 2

        private const val LAP_VAR_REJECT = 20.0      // definitely blurry below this
        private const val LAP_VAR_PASS = 90.0        // definitely sharp above this
        private const val TENENGRAD_EXPECTED_MAX = 2000.0 // used to normalize Tenengrad
        private const val COMBINED_ACCEPT_THRESHOLD = 0.52 // 0..1 (0.5-ish)
        private const val REQUIRED_CONSECUTIVE_CLEAR_FRAMES = 1

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        // Get intent extras (stored for later use)
        // Note: These are intentionally unused but kept for future functionality
        @Suppress("UNUSED_VARIABLE")
        val _captureMode = intent.getStringExtra(EXTRA_MODE) ?: "register"

        @Suppress("UNUSED_VARIABLE")
        val _userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusTextCameraCapture)
        qualityText = findViewById(R.id.qualityText)
        torchButton = findViewById<ImageButton>(R.id.torchButton)

        torchButton.setOnClickListener {
            toggleTorch()
        }

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        hybridDetector = HybridDetector(this)
        if (hybridDetector.initialize()) {
            Log.d(TAG, "Hybrid detector initialized successfully")
            modelLabel = when (hybridDetector.getDetectorType()) {
                HybridDetector.DetectorType.TFLITE -> "Model - TFLite"
                else -> "Model - ONNX"
            }
            val dummyBitmap = createBitmap(640, 640)
            hybridDetector.detect(dummyBitmap)
        } else {
            Log.e(TAG, "Failed to initialize hybrid detector")
            modelLabel = "Model - Not available"
        }

        combinedQualityAssessor = CombinedQualityAssessor(hybridDetector)
        Log.d(TAG, "Quality assessor initialized")

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "Surface texture available: ${width}x${height}")
                configureTransform(width, height)
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "Surface texture size changed: ${width}x${height}")
                configureTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        textureView.viewTreeObserver.addOnGlobalLayoutListener {
            onViewLayoutChanged()
        }

        if (com.tcc.fingerprint.utils.Utils.checkCameraPermission(this@CaptureActivity)) {
            startBackgroundThread()
        } else {
            requestCameraPermission()
        }
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // ✅ SAMADHI OPTIMIZATION: Find macro camera with best focus distance
        val cameraId = findBestMacroCamera() ?: findFallbackCamera()

        if (cameraId == null) {
            Log.e(TAG, "No suitable camera found")
            runOnUiThread {
                statusText.text = "No suitable camera available"
            }
            return
        }

        Log.d(TAG, "Selected camera ID: $cameraId")

        // 🔍 DEBUG: Log camera sensor orientation
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        Log.d(TAG, "DEBUG: Camera SENSOR_ORIENTATION: $sensorOrientation degrees")
        Log.d(TAG, "DEBUG: Camera SENSOR_SIZE: ${sensorSize?.width}x${sensorSize?.height}")
        Log.d(TAG, "DEBUG: Device orientation: ${resources.configuration.orientation}")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                // ✅ FIX: Set preview size before creating session
                val bestPreview = getBestPreviewSize()
                previewSize = bestPreview
                Log.d(TAG, "Using preview resolution: ${bestPreview.width}x${bestPreview.height}")

                // ✅ ROBUST CAMERA2 PATTERN: Setup ImageReader for high-quality capture
                setupImageReader()

                createCameraPreviewSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                Log.e(TAG, "Camera open error: $error")
            }
        }, backgroundHandler)
    }

    private fun findBestMacroCamera(): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

            // Only consider back cameras
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            // Check for macro capabilities
            val availableCapabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasMacro =
                availableCapabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

            // Check focus distance (macro cameras have shorter minimum focus distance)
            val focusDistanceRange =
                characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val hasGoodFocusDistance =
                focusDistanceRange != null && focusDistanceRange > 0 && focusDistanceRange < 0.1f

            if (hasMacro || hasGoodFocusDistance) {
                Log.d(TAG, "Found macro camera: $cameraId (focus distance: $focusDistanceRange)")
                return cameraId
            }
        }
        return null
    }

    private fun findFallbackCamera(): String? {
        return cameraManager.cameraIdList.find { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            // ✅ SIMPLIFIED: Use fixed 1920x1080 like the working code
            surfaceTexture?.setDefaultBufferSize(1920, 1080)

            val previewSurface = Surface(surfaceTexture)
            captureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(previewSurface)

            // ✅ SIMPLIFIED: Include both preview and ImageReader surfaces
            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurface)

            // Add ImageReader surface if available
            imageReader?.let { reader ->
                surfaces.add(reader.surface)
                Log.d(TAG, "Added ImageReader surface to session")
            }

            // ✅ SIMPLIFIED: Configure session with fixed resolution
            // Use newer API to avoid deprecation warning
            @Suppress("DEPRECATION")
            cameraDevice!!.createCaptureSession(
                surfaces.toList(),
                object : CameraCaptureSession.StateCallback() {
                    @SuppressLint("NewApi")
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        // ✅ SIMPLIFIED: Apply basic camera settings
                        try {
                            captureRequestBuilder?.let { builder ->
                                // 🎯 ENHANCED: Optimized camera configuration for fingerprint capture
                                // Autofocus: Continuous picture mode for optimal focus tracking
                                builder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
                                // Exposure: Auto mode with focus priority
                                builder.set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )

                                // White balance: Auto for consistent lighting
                                builder.set(
                                    CaptureRequest.CONTROL_AWB_MODE,
                                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                                )

                                // 🎯 FOCUS OPTIMIZATION: Set focus distance for close-up fingerprint capture
                                // This helps the camera focus better at close distances (2-5cm)
                                builder.set(
                                    CaptureRequest.CONTROL_AF_TRIGGER,
                                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                                )

                                // 🏭 STABLE V1.0: Configure ROI-based focus areas for D-overlay
                                // This ensures camera focuses specifically on the fingerprint area
//                                configureROIFocusAreas(builder)
                                overlayView.post {
                                    configureROIFocusAreas(builder)
                                }

                                // ✅ FIX: Set torch ON during preview (not just capture)
                                builder.set(
                                    CaptureRequest.FLASH_MODE,
                                    CaptureRequest.FLASH_MODE_TORCH
                                )

                                session.setRepeatingRequest(
                                    builder.build(),
                                    null,
                                    backgroundHandler
                                )
                                Log.d(TAG, "Camera preview session configured with TORCH ON")

                                // Set camera ready and start monitoring
                                isCameraReady = true

                                runOnUiThread {
                                    statusText.text = "Camera ready - Position finger in overlay"
                                    startAutoCaptureMonitoring()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting repeating request: ${e.message}")
                            runOnUiThread {
                                statusText.text = "Camera configuration failed"
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure camera session")
                        runOnUiThread {
                            statusText.text = "Camera configuration failed"
                        }
                    }
                },
                backgroundHandler
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera preview session: ${e.message}")
        }
    }

    private fun getBestPreviewSize(): android.util.Size {
        // Always use 1920x1080 for consistent behavior across all devices
        return android.util.Size(1920, 1080)
    }

    private fun setupImageReader() {
        // 🎯 V1.0.8: FIXED - Properly assign ImageReader to class variable
        imageReader = ImageReader.newInstance(
            CAPTURE_WIDTH, CAPTURE_HEIGHT, ImageFormat.JPEG, 1
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    Log.d(TAG, "ImageReader: JPEG captured ${bytes.size} bytes")

                    // Process the captured image
                    processCapturedImageFromBytes(bytes)

                } finally {
                    image.close()
                }
            }
        }, backgroundHandler)

        Log.d(TAG, "ImageReader properly initialized: ${imageReader?.width}x${imageReader?.height}")
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        // Use the TextureView display rectangle as content bounds for overlay
        val contentRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        overlayView.setContentBounds(contentRect)
        overlayView.invalidate()

        // Ensure onDraw runs and roiBounds is calculated before we use it for camera regions:
        overlayView.post {
            // Now safe: overlayView.roiBounds should be non-null
            Log.d(TAG, "OverlayView roiBounds after layout: ${overlayView.getRoiBounds()}")
        }
    }

    private fun configureROIFocusAreas(builder: CaptureRequest.Builder) {
        try {
            val overlayBounds = overlayView.getRoiBounds()
            if (overlayBounds != null) {
                // Get camera characteristics for sensor size
                val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
                val sensorSize =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

                val viewW = textureView.width.toFloat()
                val viewH = textureView.height.toFloat()
                if (viewW == 0f || viewH == 0f) {
                    Log.w(TAG, "TextureView dimensions are zero")
                    return
                }

                if (sensorSize != null) {
                    // Calculate scale factors from view coordinates to sensor coordinates
//                    val scaleX = sensorSize.width.toFloat() / 1920f  // Fixed preview width
//                    val scaleY = sensorSize.height.toFloat() / 1080f // Fixed preview height
//
//                    // Convert overlay bounds to sensor coordinates
//                    val roiLeft = (overlayBounds.left * scaleX).toInt()
//                    val roiTop = (overlayBounds.top * scaleY).toInt()
//                    val roiRight = (overlayBounds.right * scaleX).toInt()
//                    val roiBottom = (overlayBounds.bottom * scaleY).toInt()
//
//                    val roiWidth = roiRight - roiLeft
//                    val roiHeight = roiBottom - roiTop
//
//                    // Create ROI metering region for D-overlay area
//                    val meteringRegion = MeteringRectangle(
//                        roiLeft, roiTop, roiWidth, roiHeight,
//                        MeteringRectangle.METERING_WEIGHT_MAX
//                    )

                    val scaleX = sensorSize.width.toFloat() / viewW
                    val scaleY = sensorSize.height.toFloat() / viewH

                    val roiLeft = (overlayBounds.left * scaleX).toInt()
                    val roiTop = (overlayBounds.top * scaleY).toInt()
                    val roiRight = (overlayBounds.right * scaleX).toInt()
                    val roiBottom = (overlayBounds.bottom * scaleY).toInt()

                    val roiWidth = roiRight - roiLeft
                    val roiHeight = roiBottom - roiTop

                    val meteringRegion = MeteringRectangle(
                        roiLeft.coerceIn(0, sensorSize.width - 1),
                        roiTop.coerceIn(0, sensorSize.height - 1),
                        roiWidth.coerceAtLeast(1).coerceAtMost(sensorSize.width),
                        roiHeight.coerceAtLeast(1).coerceAtMost(sensorSize.height),
                        MeteringRectangle.METERING_WEIGHT_MAX
                    )

                    // 🏭 STABLE V1.0: Set focus and exposure regions to D-overlay area
                    // This ensures camera focuses specifically on the fingerprint area
//                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRegion))
//                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRegion))

                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRegion))
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRegion))


                    Log.d(TAG, "STABLE V1.0: ROI focus areas configured for D-overlay")
                    Log.d(TAG, "Focus target: ${roiWidth}x${roiHeight} at (${roiLeft},${roiTop})")
                    Log.d(TAG, "Result: Camera will focus specifically on fingerprint area")
                } else {
                    Log.w(TAG, "⚠️ Sensor size not available, using default focus")
                }
            } else {
                Log.w(TAG, "⚠️ Overlay bounds not available, using default focus")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to configure ROI focus areas: ${e.message}")
            Log.d(TAG, "Fallback: Using default camera focus (may result in blurry fingerprints)")
        }
    }

    private fun updatePreview() {
        captureRequestBuilder?.let { builder ->
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        }
    }

    private fun startAutoCaptureMonitoring() {
        if (isAutoCaptureMode) {
            isMonitoringStopped = false
            autoCaptureStartTime = System.currentTimeMillis()
            Log.d(TAG, "Starting auto-capture monitoring")
            lifecycleScope.launch(Dispatchers.Main) {
                while (!isMonitoringStopped && isAutoCaptureMode && !isCapturing && !hasCaptured) {
//                    val currentTime = System.currentTimeMillis()
//                    if (currentTime - autoCaptureStartTime > 30000) {
//                        Log.w(TAG, "Auto-capture timeout - stopping")
//                        stopAutoCaptureMonitoring()
//                        break
//                    }
                    performAutoCaptureAsync()
                    delay(150)
                }
            }
        }
    }

    private fun stopAutoCaptureMonitoring() {
        // 🎯 V1.0.8: REAL FIX - Actually stop the monitoring thread
        isMonitoringStopped = true
        autoCaptureStartTime = 0L
        autoCaptureHandler.removeCallbacks(autoCaptureRunnable)
        Log.d(TAG, "Auto-capture monitoring stopped")
    }

    private suspend fun performAutoCaptureAsync() = withContext(Dispatchers.IO) {
        Log.d(TAG, "performAutoCapture() called")
        val bitmap = textureView.bitmap
        if (bitmap != null) {
            Log.d(TAG, "Bitmap available: ${bitmap.width}x${bitmap.height}")
            val qualityAssessment = combinedQualityAssessor?.assessQuality(bitmap)
            withContext(Dispatchers.Main) {
                if (qualityAssessment != null) {
                    Log.d(
                        TAG,
                        "Quality assessment received: AI=${(qualityAssessment.aiConfidence * 100).toInt()}%, Fingers=${qualityAssessment.fingerCount}"
                    )
                    // ✅ Update quality display
                    updateQualityDisplay(qualityAssessment)

                    // ✅ Check if ready for capture
                    if (qualityAssessment.isQuality) {
                        // 🎯 Validate finger position and quality
                        if (validateQualityForCapture(qualityAssessment)) {
                            // 🧪 Stability gate: IoU ≥ 0.5 and size ±30% across last 5 frames
                            val roiBounds = overlayView.getRoiBounds()
                            val stabilityOk = if (roiBounds != null) {
                                val stable = hybridDetector.shouldAutoCapture(roiBounds)
                                Log.d(TAG, "Stability result: $stable (5-frame IoU/size gate)")
                                stable
                            } else {
                                Log.d(TAG, "Stability skipped: ROI bounds are null")
                                false
                            }

                            if (stabilityOk) {
                                if (!isCountdownActive) {
                                    // 🎯 USER ADJUSTMENT PERIOD: Give user time to adjust fingers
                                    // Industry best practice: 2-3 seconds for user fine-tuning
                                    Log.d(
                                        TAG,
                                        "Stability passed - starting user adjustment period (3 seconds)"
                                    )
                                    isCapturing = true
                                    captureImage()
//                                startUserAdjustmentPeriod()
                                } else {
                                    Log.d(TAG, "Countdown already active, continuing...")
                                }
                            } else {
                                if (!isCountdownActive) {
                                    // 🎯 Show simple stabilizing progress without counters
                                    val hist = hybridDetector.getDetectionHistory().size
                                    if (hist in 1..4) {
                                        val msg = "Stabilizing… (${hist}/5 frames)"
                                        runOnUiThread {
                                            statusText.text = msg
                                            overlayView.showMessage(msg)
                                        }
                                    } else {
                                        runOnUiThread { overlayView.hideMessage() }
                                    }
                                    Log.d(
                                        TAG,
                                        "Stability gate not yet passed; waiting for more stable frames (hist=$hist/5)"
                                    )
                                } else {
                                    Log.d(TAG, "Countdown in progress - ignoring stability drop")
                                }
                            }
                        } else {
                            // 🎯 Stop countdown if validation fails (only if not already in countdown)
                            if (!isCountdownActive) {
                                Log.d(TAG, "Stopping countdown - validation failed")
                                stopCountdown()
                                runOnUiThread {
                                    overlayView.showMessage("Adjust finger position")
                                }
                            } else {
                                Log.d(TAG, "Countdown in progress - ignoring quality drop")
                            }
                        }
                    } else {
                        // 🎯 Stop countdown if not ready for capture (ALWAYS stop if no valid detections)
                        if (qualityAssessment.fingerCount == 0) {
                            Log.d(TAG, "STOPPING countdown - NO FINGERS DETECTED")
                            stopCountdown()
                            runOnUiThread { overlayView.showMessage("Place fingers in D-shape") }
                        } else if (!isCountdownActive) {
                            Log.d(TAG, "Stopping countdown - quality not acceptable")
                            stopCountdown()
                            runOnUiThread {
                                overlayView.showMessage(
                                    getQualityStatus(
                                        qualityAssessment
                                    )
                                )
                            }
                        } else {
                            Log.d(TAG, "Countdown in progress - ignoring quality drop")
                        }
                    }
                } else {
                    Log.d(TAG, "Quality assessment is NULL")
                    // ✅ Quality assessor not available
                    runOnUiThread { overlayView.showMessage("Quality not available") }
                }
            }
        }
    }

    private fun updateQualityDisplay(assessment: CombinedQualityAssessor.QualityAssessment) {
        runOnUiThread {
            // ✅ Simple quality metrics
            val aiConfidence = (assessment.aiConfidence * 100).toInt()
            val fingerCount = assessment.fingerCount

            // ✅ Update UI with simple metrics
            qualityText.text = """
                🧠 $modelLabel
                🤖 AI Confidence: ${aiConfidence}%
                🖐️ Finger Count: $fingerCount
                📱 Status: ${if (assessment.isQuality) "READY" else "NEEDS IMPROVEMENT"}
                💡 Tip: ${assessment.recommendation}
            """.trimIndent()

            // ✅ Debug info for logging
            Log.d(
                TAG,
                "Quality Assessment - AI: ${aiConfidence}%, Fingers: ${fingerCount}, Quality: ${assessment.isQuality}"
            )
        }
    }

    private fun validateQualityForCapture(assessment: CombinedQualityAssessor.QualityAssessment): Boolean {
        // ✅ REQUIRE 3-4 FINGERS for optimal fingerprint capture
        if (assessment.fingerCount < 3 || assessment.fingerCount > 4) {
            Log.d(
                TAG,
                "Finger Capture validation FAILED: fingerCount=${assessment.fingerCount} (need 3-4)"
            )
            return false
        }
        if (assessment.aiConfidence < 0.25f) {
            Log.d(
                TAG,
                "Finger Capture validation FAILED: aiConfidence=${(assessment.aiConfidence * 100).toInt()}% (need 25%+)"
            )
            return false
        }
        if (!assessment.isQuality) {
            Log.d(TAG, "Finger Capture validation FAILED: isQuality=false")
            return false
        }

        Log.d(
            TAG,
            "Finger Capture validation PASSED: fingers=${assessment.fingerCount}, confidence=${(assessment.aiConfidence * 100).toInt()}%"
        )
        return true
    }

    private fun getQualityStatus(assessment: CombinedQualityAssessor.QualityAssessment): String {
        return assessment.recommendation
    }

    private fun stopCountdown() {
        isCountdownActive = false
        countdownHandler.removeCallbacks(countdownRunnable)
        overlayView.hideCountdown()
    }

    private fun captureImage() {
        if (cameraDevice == null) return

        isCapturing = true
        statusText.text = "Capturing image..."

        // Show capture indicator
        overlayView.showCaptureIndicator(true)

        val captureRequestBuilder =
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

        // ✅ ROBUST CAMERA2 PATTERN: Use ImageReader surface for high-quality capture
        if (imageReader != null) {
            captureRequestBuilder.addTarget(imageReader!!.surface)
            Log.d(
                TAG,
                "Using ImageReader for capture: ${imageReader!!.width}x${imageReader!!.height}"
            )
        } else {
            // 🎯 V1.0.8: FIXED - Proper TextureView fallback that actually processes images
            val surfaceTexture = textureView.surfaceTexture
            val surface = Surface(surfaceTexture)
            captureRequestBuilder.addTarget(surface)
            Log.w(TAG, "⚠️ ImageReader not available, using TextureView fallback")

            // 🎯 V1.0.8: Add TextureView image processing after capture
            Handler(Looper.getMainLooper()).postDelayed({
                if (isCapturing && !hasCaptured) {
                    Log.d(TAG, "Processing TextureView fallback image")
                    processTextureViewImage()
                }
            }, 500) // Process after 500ms to ensure capture is complete
        }

        // Professional capture settings
        captureRequestBuilder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        captureRequestBuilder.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_START
        )
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        captureRequestBuilder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

        // Keep torch state consistent
        captureRequestBuilder.set(
            CaptureRequest.FLASH_MODE,
            if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )

        Log.d(TAG, "CAPTURE: Torch ${if (isTorchOn) "ON" else "OFF"} - FP solution")

        // 🎯 HIGH-QUALITY CAPTURE SETTINGS FOR FINGERPRINT
        captureRequestBuilder.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
        captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        captureRequestBuilder.set(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
        )

        // 📸 OPTIMAL JPEG QUALITY FOR FINGERPRINT (C:\FP optimized)
        captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 100)

        // ✅ NO ORIENTATION CODE: Let camera sensor deliver image as-is
        // DO NOT set JPEG_ORIENTATION - leave it unset/default

        // ✅ Basic camera stability
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

        // ✅ CHANGE: OIS conditional for capture like TFLite-Samadhi
        if (isStabilizationEnabled) {
            captureRequestBuilder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            Log.d(TAG, "Capture with OIS enabled")
        }

        // 🎯 AE COMPENSATION FOR OPTIMAL EXPOSURE
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)

        // 🔥 AWB LOCK FOR CONSISTENT COLOR
        captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false)

        captureSession?.capture(
            captureRequestBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)

                    // Reset focus trigger after capture
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                    )
                    updatePreview()

                    // ✅ ROBUST CAMERA2 PATTERN: ImageReader will handle image processing
                    // The ImageReader's OnImageAvailableListener will call processCapturedImageFromBytes
                    Log.d(TAG, "Capture completed - ImageReader will process JPEG")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e(TAG, "Capture failed: ${failure.reason}")
                    isCapturing = false
                    runOnUiThread {
                        statusText.text = "Capture failed - Try again"
                    }
                }
            },
            backgroundHandler
        )
    }

    private fun processCapturedImageFromBytes(bytes: ByteArray) =
        lifecycleScope.launch(Dispatchers.IO) {
            var fullBitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null
            var croppedFile: File? = null
            var rawFile: File? = null

            try {
                // Decode JPEG bytes (IO)
                fullBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("Failed to decode JPEG bytes to Bitmap")
                Log.d(TAG, "Decoded JPEG: ${fullBitmap.width}x${fullBitmap.height}")

                // Resize to a fixed max side for metrics consistency (keeps compute stable across sizes)
                val maxSide = 800
                val scaledBitmap = if (fullBitmap.width > maxSide || fullBitmap.height > maxSide) {
                    val scale = maxSide.toFloat() / maxOf(fullBitmap.width, fullBitmap.height)
                    fullBitmap.scale(
                        (fullBitmap.width * scale).toInt(),
                        (fullBitmap.height * scale).toInt()
                    )
                } else {
                    fullBitmap
                }

                // Compute blur-related metrics (Laplacian variance + Tenengrad)
                val lapVar = try {
                    computeLaplacianVariance(scaledBitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Laplacian computation failed: ${e.message}")
                    Double.NaN
                }

                val tenengrad = try {
                    computeTenengradScore(scaledBitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Tenengrad computation failed: ${e.message}")
                    Double.NaN
                }

                // Free scaledBitmap if it is not same reference as fullBitmap
                if (scaledBitmap !== fullBitmap) {
                    scaledBitmap.recycle()
                }

                Log.d(TAG, "Blur metrics -> lapVar=$lapVar, tenengrad=$tenengrad")

                // If lapVar is finite and obviously low -> immediate retake
                if (lapVar.isFinite() && lapVar < LAP_VAR_REJECT) {
                    Log.w(TAG, "Image too blurry via lapVar ($lapVar) -> immediate retake")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Image blurry — Retaking..."
                        overlayView.showCaptureIndicator(false)
                        isCapturing = false
                    }
                    withContext(Dispatchers.Main) { startAutoCaptureMonitoring() }
                    fullBitmap.recycle()
                    return@launch
                }

                // Build normalized indicators (0..1)
                val lapNorm = if (lapVar.isFinite()) {
                    ((lapVar - LAP_VAR_REJECT) / (LAP_VAR_PASS - LAP_VAR_REJECT)).coerceIn(0.0, 1.0)
                } else 0.0

                val tenNorm = if (tenengrad.isFinite()) {
                    // normalize by an expected maximum (tune TENENGRAD_EXPECTED_MAX on-device)
                    (tenengrad / TENENGRAD_EXPECTED_MAX).coerceIn(0.0, 1.0)
                } else 0.0

                // Weighted combine (give Laplacian more weight)
                val combinedScore = 0.7 * lapNorm + 0.3 * tenNorm
                Log.d(
                    TAG,
                    "Normalized indicators -> lapNorm=$lapNorm, tenNorm=$tenNorm, combined=$combinedScore"
                )

                // Decide acceptance using combined score
                if (combinedScore < COMBINED_ACCEPT_THRESHOLD) {
                    // borderline/poor - request new capture (increase tolerance by tuning COMBINED_ACCEPT_THRESHOLD)
                    consecutiveClearFrames = 0
                    Log.w(TAG, "Combined quality below threshold ($combinedScore) -> retake")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Stabilizing... hold steady"
                        overlayView.showCaptureIndicator(false)
                        isCapturing = false
                    }
                    withContext(Dispatchers.Main) {
                        delay(150)
                        startAutoCaptureMonitoring()
                    }
                    fullBitmap.recycle()
                    return@launch
                }

                // Passed blur/clarity checks -> increment consecutiveClearFrames & proceed only if satisfies requirement
                consecutiveClearFrames++
                Log.d(
                    TAG,
                    "Clear frame (${consecutiveClearFrames}/${REQUIRED_CONSECUTIVE_CLEAR_FRAMES})"
                )

                if (consecutiveClearFrames < REQUIRED_CONSECUTIVE_CLEAR_FRAMES) {
                    Log.d(TAG, "Need more clear frames - requesting another capture")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Stabilizing... hold steady"
                        overlayView.showCaptureIndicator(false)
                        isCapturing = false
                    }
                    withContext(Dispatchers.Main) {
                        delay(150)
                        startAutoCaptureMonitoring()
                    }
                    fullBitmap.recycle()
                    return@launch
                }

                // Reset counters for next capture session
                consecutiveClearFrames = 0

                // At this point the image is acceptable. Save raw JPEG (optional)
                rawFile = File(
                    getExternalFilesDir(null),
                    "fingerprint_raw_${System.currentTimeMillis()}.jpg"
                )
                FileOutputStream(rawFile).use { out -> out.write(bytes) }
                Log.d(TAG, "Raw JPEG saved: ${rawFile.absolutePath}")

                // Crop and process fingerprint
                croppedBitmap = cropToROI(fullBitmap)
                Log.d(TAG, "Cropped to ROI: ${croppedBitmap.width}x${croppedBitmap.height}")

                val processingResult = FingerprintProcessor.processFingerprint(croppedBitmap, null)
                if (processingResult == null) {
                    Log.w(TAG, "OpenCV processing returned null -> retake")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Processing failed — Retaking..."
                        overlayView.showCaptureIndicator(false)
                        isCapturing = false
                    }
                    withContext(Dispatchers.Main) { startAutoCaptureMonitoring() }
                    fullBitmap.recycle()
                    croppedBitmap.recycle()
                    return@launch
                }

                Log.d(
                    TAG,
                    "OpenCV processing completed: quality=${processingResult.qualityScore}, acceptable=${processingResult.isAcceptable}"
                )

                if (!processingResult.isAcceptable) {
                    Log.w(TAG, "Image quality below threshold after processing - requesting retake")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Poor image quality - Please retake"
                        overlayView.showCaptureIndicator(false)
                        isCapturing = false
                        hasCaptured = false
                    }
                    withContext(Dispatchers.Main) { startAutoCaptureMonitoring() }
                    fullBitmap.recycle()
                    croppedBitmap.recycle()
                    return@launch
                }

                // Save cropped image with white background (IO)
                val outFile = File(
                    getExternalFilesDir(null),
                    "fingerprint_cropped_${System.currentTimeMillis()}.jpg"
                )
                try {
                    val toSave = if (croppedBitmap.hasAlpha()) {
                        val withWhite = createBitmap(croppedBitmap.width, croppedBitmap.height)
                        val c = Canvas(withWhite)
                        c.drawColor(Color.WHITE)
                        c.drawBitmap(croppedBitmap, 0f, 0f, null)
                        withWhite
                    } else {
                        croppedBitmap
                    }
                    FileOutputStream(outFile).use { fos ->
                        toSave.compress(
                            Bitmap.CompressFormat.PNG,
                            95,
                            fos
                        )
                    }
                    if (toSave !== croppedBitmap) toSave.recycle()
                    croppedFile = outFile
                    Log.d(TAG, "Cropped JPEG saved: ${outFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save cropped JPEG: ${e.message}")
                }

                // ---------- Launch preview on MAIN with CROPPED file ----------
                withContext(Dispatchers.Main) {
                    val imagePath = croppedFile?.absolutePath ?: ""
                    val incoming = this@CaptureActivity.intent
                    val uid = incoming.getStringExtra(EXTRA_USER_ID) ?: ""
                    val mode = incoming.getStringExtra(EXTRA_MODE) ?: "register"

                    val intent =
                        Intent(this@CaptureActivity, ImageCropperActivity::class.java).apply {
                            putExtra(ImageCropperActivity.EXTRA_IMAGE_PATH, imagePath)
                            putExtra(PreviewActivity.EXTRA_USER_ID, uid)
                            putExtra(PreviewActivity.EXTRA_MODE, mode)
                        }
                    startActivity(intent)

                    hasCaptured = true
                    isCapturing = false
                    statusText.text =
                        "Image captured successfully - use Save button to save to gallery"
                    overlayView.showCaptureIndicator(false)
                    startAutoCaptureMonitoring()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured JPEG bytes: ${e.message}")
                withContext(Dispatchers.Main) {
                    isCapturing = false
                    statusText.text = "Error processing image: ${e.message}"
                    overlayView.showCaptureIndicator(false)
                }
            } finally {
                try {
                    fullBitmap?.recycle()
                    croppedBitmap?.recycle()
                } catch (ignored: Exception) {
                }
            }
        }

    @Throws(Exception::class)
    private fun computeLaplacianVariance(bitmap: Bitmap): Double {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)

        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val stdDev = std.toArray()[0]
        val lapVar = stdDev * stdDev

        // release mats
        listOf(mat, gray, lap, mean, std).forEach { it.release() }
        return lapVar
    }

    @Throws(Exception::class)
    private fun computeTenengradScore(bitmap: Bitmap): Double {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_64F, 1, 0, 3)
        Imgproc.Sobel(gray, gy, CvType.CV_64F, 0, 1, 3)

        val mag = Mat()
        Core.magnitude(gx, gy, mag)

        // compute mean of squared magnitude (robustness to bright edges)
        val sq = Mat()
        Core.multiply(mag, mag, sq)
        val mean = Core.mean(sq).`val`[0]

        // release mats
        listOf(mat, gray, gx, gy, mag, sq).forEach { it.release() }
        return mean
    }

    private fun processTextureViewImage() {
        try {
            Log.d(TAG, "Processing TextureView image")

            // Get the current bitmap from the TextureView
            val bitmap = textureView.bitmap

            if (bitmap == null) {
                Log.w(TAG, "TextureView bitmap is null, cannot process image.")
                return
            }

            Log.d(TAG, "TextureView bitmap: ${bitmap.width}x${bitmap.height}")

            // Crop to ROI using the preview content rectangle for accurate mapping
            val croppedBitmap = cropToROI(bitmap)

            Log.d(TAG, "Cropped TextureView bitmap: ${croppedBitmap.width}x${croppedBitmap.height}")

            // Process the cropped image with OpenCV
            // 🎯 V1.0.3: Pass null for roiBounds since image is already cropped to D-overlay
            // This prevents FingerprintProcessor from trying to crop an already-cropped image
            val processingResult = FingerprintProcessor.processFingerprint(croppedBitmap, null)

            if (processingResult != null) {
                Log.d(TAG, "OpenCV processing completed:")
                Log.d(TAG, "  - Overall Quality: ${processingResult.qualityScore}")
                Log.d(TAG, "  - Acceptable: ${processingResult.isAcceptable}")
                Log.d(TAG, "  - Recommendation: ${processingResult.recommendation}")

                // Check if image quality is acceptable
                if (!processingResult.isAcceptable) {
                    Log.w(TAG, "Image quality below threshold - requesting retake")
                    runOnUiThread {
                        statusText.text = "Poor image quality - Please retake"
                        overlayView.showCaptureIndicator(false)
                    }
                    isCapturing = false
                    return
                }

                // Save the processed image to a temporary file
                val file =
                    File(getExternalFilesDir(null), "fingerprint_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
                Log.d(TAG, "TextureView processed image saved: ${file.absolutePath}")

                // Start preview activity with the processed image path
                val incoming = this.intent
                val uid = incoming.getStringExtra(EXTRA_USER_ID) ?: ""
                val mode = incoming.getStringExtra(EXTRA_MODE) ?: "register"
                val previewIntent = Intent(this, PreviewActivity::class.java).apply {
                    putExtra(PreviewActivity.EXTRA_IMAGE_PATH, file.absolutePath)
                    putExtra(PreviewActivity.EXTRA_USER_ID, uid)
                    putExtra(PreviewActivity.EXTRA_MODE, mode)
                }
                startActivity(previewIntent)

                hasCaptured = true
                isCapturing = false

                // 🎯 V1.0.8: REAL FIX - Restart monitoring after successful capture
                Log.d(
                    TAG,
                    "TextureView capture completed successfully - restarting monitoring for next capture"
                )
                startAutoCaptureMonitoring()  // Restart monitoring for next capture

                runOnUiThread {
                    statusText.text =
                        "Image captured successfully - use Save button to save to gallery"
                    overlayView.showCaptureIndicator(false)
                }
            } else {
                Log.e(TAG, "OpenCV processing failed for TextureView image")
                isCapturing = false
                runOnUiThread {
                    statusText.text = "Error processing image: OpenCV processing failed"
                    overlayView.showCaptureIndicator(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing TextureView image: ${e.message}")
            isCapturing = false
            runOnUiThread {
                statusText.text = "Error processing image: ${e.message}"
                overlayView.showCaptureIndicator(false)
            }
        }
    }

    private fun cropToROI(bitmap: Bitmap): Bitmap {
        try {
            val overlayBounds = overlayView.getRoiBounds()

            if (overlayBounds==null){
                Log.w(TAG, "ROI bounds null, skipping crop")
                return bitmap
            }

            val viewWidth = textureView.width.toFloat()
            val viewHeight = textureView.height.toFloat()

            val scaleX = bitmap.width.toFloat() / viewWidth
            val scaleY = bitmap.height.toFloat() / viewHeight

            val cropLeft = (overlayBounds.left * scaleX).toInt().coerceAtLeast(0)
            val cropTop = (overlayBounds.top * scaleY).toInt().coerceAtLeast(0)
            val cropWidth = (overlayBounds.width() * scaleX).toInt().coerceAtMost(bitmap.width - cropLeft)
            val cropHeight = (overlayBounds.height() * scaleY).toInt().coerceAtMost(bitmap.height - cropTop)

            if (cropWidth <= 0 || cropHeight <= 0) return bitmap

            val rectCrop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)

            // Create masked bitmap (starts transparent)
            val masked = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(masked)

            // Transform path to crop coords
            val path = Path(overlayView.roiPath) // copy
            val matrix = Matrix().apply {
                postTranslate(-overlayBounds.left, -overlayBounds.top)
                postScale(cropWidth.toFloat() / overlayBounds.width(), cropHeight.toFloat() / overlayBounds.height())
            }
            path.transform(matrix)

            // Clip to path (only draw inside D)
            canvas.clipPath(path)

            // Draw rectCrop (only inside path is filled)
            canvas.drawBitmap(rectCrop, 0f, 0f, null)

            rectCrop.recycle()

            // Optional: Add ridge check (implement with OpenCV Canny edge count > threshold)
            // if (!hasClearRidges(masked)) {
            //     Log.w(TAG, "Masked crop rejected: Poor ridges")
            //     masked.recycle()
            //     return bitmap
            // }

            Log.d(TAG, "D-shape masked crop successful: ${cropWidth}x${cropHeight}")
            return masked
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed: ${e.message}")
            return bitmap
        }
    }

    private fun startBackgroundThread() {
        backgroundThread =
            HandlerThread("CameraBackground", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.d(TAG, "Background thread started")
        // If permission is granted and surface is ready but camera not opened yet, open now
        if (com.tcc.fingerprint.utils.Utils.checkCameraPermission(this@CaptureActivity) && textureView.isAvailable && cameraDevice == null) {
            Log.d(TAG, "Opening camera after background thread start")
            openCamera()
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBackgroundThread()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        stopAutoCaptureMonitoring()
        lifecycleScope.coroutineContext.cancelChildren()
    }

    override fun onResume() {
        super.onResume()
        if (backgroundHandler == null) {
            startBackgroundThread()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        stopBackgroundThread()
        stopAutoCaptureMonitoring()
        hybridDetector.close()
        // Quality assessor cleanup handled automatically
    }

    private fun closeCamera() {
        try {
            Log.d(TAG, "Closing camera")
            isCameraReady = false

            // ✅ ROBUST CAMERA2 PATTERN: Close ImageReader first
            imageReader?.close()
            imageReader = null

            // Close session first
            captureSession?.close()
            captureSession = null

            // Close camera device
            cameraDevice?.close()
            cameraDevice = null

            Log.d(TAG, "Camera closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        }
    }

    private fun onViewLayoutChanged() {
        val currentWidth = textureView.width
        val currentHeight = textureView.height

        if (currentWidth > 0 && currentHeight > 0) {
            Log.d(TAG, "View layout changed: ${currentWidth}x${currentHeight}")

            // Check if we need to update preview size
            val currentPreviewSize = previewSize
            if (currentPreviewSize != null) {
                val currentAspect = currentWidth.toFloat() / currentHeight
                val previewAspect = currentPreviewSize.width.toFloat() / currentPreviewSize.height
                val aspectDiff = kotlin.math.abs(currentAspect - previewAspect)

                // If aspect ratio difference is significant, consider updating
                if (aspectDiff > 0.1f) {
                    Log.d(
                        TAG,
                        "Significant aspect ratio change detected: view=${
                            String.format(
                                "%.2f",
                                currentAspect
                            )
                        }, preview=${String.format("%.2f", previewAspect)}"
                    )

                    // Only update if camera is not actively capturing
                    if (!isCapturing && !isAutoCaptureMode) {
                        Log.d(TAG, "Updating preview size due to aspect ratio change")
                        updatePreviewSize()
                    }
                }
            }

            // Update transform with new dimensions
            configureTransform(currentWidth, currentHeight)
        }
    }

    private fun updatePreviewSize() {
        try {
            val newPreviewSize = getBestPreviewSize()
            val currentPreviewSize = previewSize

            if (currentPreviewSize == null ||
                newPreviewSize.width != currentPreviewSize.width ||
                newPreviewSize.height != currentPreviewSize.height
            ) {

                Log.d(
                    TAG,
                    "Preview size changed: ${currentPreviewSize?.width}x${currentPreviewSize?.height} → ${newPreviewSize.width}x${newPreviewSize.height}"
                )

                previewSize = newPreviewSize

                // Reconfigure camera if session exists
                if (captureSession != null && cameraDevice != null) {
                    Log.d(TAG, "Reconfiguring camera with new preview size")
                    closeCamera()
                    // Use Handler for delay instead of coroutines
                    backgroundHandler?.postDelayed({
                        if (!isFinishing) {
                            openCamera()
                        }
                    }, 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating preview size: ${e.message}")
        }
    }

    private fun toggleTorch() {
        isTorchOn = !isTorchOn

        torchButton.setImageDrawable(
            if (isTorchOn) this.getDrawable(R.drawable.ic_flash_light_on)
            else this.getDrawable(R.drawable.ic_flashlight_off)
        )

        try {
            val previewRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                    addTarget(Surface(textureView.surfaceTexture))
                    set(
                        CaptureRequest.FLASH_MODE,
                        if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                    )
                }

            captureSession?.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                null,
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle failed", e)
        }
    }

}


//    private fun cropToROI(bitmap: Bitmap): Bitmap {
//        try {
//            val overlayBounds = overlayView.getRoiBounds() ?: return bitmap
//            val viewWidth = textureView.width.toFloat()
//            val viewHeight = textureView.height.toFloat()
//
//            val scaleX = bitmap.width.toFloat() / viewWidth
//            val scaleY = bitmap.height.toFloat() / viewHeight
//
//            val cropLeft = (overlayBounds.left * scaleX).toInt().coerceAtLeast(0)
//            val cropTop = (overlayBounds.top * scaleY).toInt().coerceAtLeast(0)
//            val cropWidth =
//                (overlayBounds.width() * scaleX).toInt().coerceAtMost(bitmap.width - cropLeft)
//            val cropHeight =
//                (overlayBounds.height() * scaleY).toInt().coerceAtMost(bitmap.height - cropTop)
//
//            if (cropWidth <= 0 || cropHeight <= 0) return bitmap
//
//            val rectCrop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
//
//            // Mask to D-shape using OverlayView path
//            val masked = createBitmap(cropWidth, cropHeight)
//            val canvas = Canvas(masked)
//            canvas.drawBitmap(rectCrop, 0f, 0f, null)
//
//            // Scale path to crop size
////            val path = android.graphics.Path(overlayView.roiPath) // Assume public in OverlayView
////            val pathScaleX = cropWidth.toFloat() / overlayBounds.width()
////            val pathScaleY = cropHeight.toFloat() / overlayBounds.height()
//            val path = Path(overlayView.roiPath) // copy
//            val pathScaleX = cropWidth.toFloat() / overlayBounds.width()
//            val pathScaleY = cropHeight.toFloat() / overlayBounds.height()
////            val matrix = Matrix().apply { postScale(pathScaleX, pathScaleY) }
//            val matrix = Matrix().apply {
//                // Move path origin to crop origin (subtract overlay left/top)
//                postTranslate(-overlayBounds.left, -overlayBounds.top)
//                // then scale into crop coordinate space
//                postScale(pathScaleX, pathScaleY)
//            }
//            path.transform(matrix)
//
//// Then draw mask (CLEAR) on the canvas for masked image
//            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
//            }
//            canvas.drawPath(path, maskPaint)
////            path.transform(matrix)
////
////            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
////                xfermode =
////                    PorterDuffXfermode(PorterDuff.Mode.CLEAR)
////            }
////            canvas.drawPath(path, maskPaint) // Clear outside D-shape
//
//            // Validate
////            if (!hasClearRidges(masked)) {
////                Log.w(TAG, "Masked crop rejected: Poor ridges")
////                return bitmap
////            }
//
//            Log.d(TAG, "D-shape masked crop successful: ${cropWidth}x${cropHeight}")
//            return masked
//        } catch (e: Exception) {
//            Log.e(TAG, "Crop failed: ${e.message}")
//            return bitmap
//        }
//    }

//    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
//
//        Log.d(TAG, "Using fixed 1920x1080 - no transform needed")
//
//        val contentRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
//        overlayView.setContentBounds(contentRect)
//
//        Log.d(TAG, "Content bounds set to full view: $contentRect")
//    }
