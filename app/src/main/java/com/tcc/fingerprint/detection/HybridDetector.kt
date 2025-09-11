package com.tcc.fingerprint.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

/**
 * TFLite-only detector for finger detection
 */
class HybridDetector(private val context: Context) {
    
    private var currentDetector: FingerDetector? = null
    private var detectorType: DetectorType = DetectorType.NONE
    private val detectionHistory = mutableListOf<DetectionHistory>()
    private val maxHistorySize = 10
    
    enum class DetectorType {
        TFLITE, NONE
    }
    
    /**
     * Initialize the TFLite detector
     * @return true if initialization successful
     */
    fun initialize(): Boolean {
        return try {
            Log.d("HybridDetector", "Starting detector initialization...")

            // 1) TFLite primary
            run {
                Log.d("HybridDetector", "Testing TFLite support...")
                val tflite = TFLiteDetector(context)
                if (tflite.isSupported() && tflite.initialize()) {
                    currentDetector = tflite
                    detectorType = DetectorType.TFLITE
                    Log.d("HybridDetector", "Using TFLite detector")
                    return true
                }
                Log.w("HybridDetector", "TFLite not available; trying ONNX...")
            }

            // 2) ONNX fallback
            run {
                val onnx = OnnxDetector(context)
                if (onnx.isSupported() && onnx.initialize()) {
                    currentDetector = onnx
                    detectorType = DetectorType.TFLITE  // reuse enum; functionally TFLite-only elsewhere
                    Log.d("HybridDetector", "Using ONNX fallback detector")
                    return true
                }
            }

            Log.e("HybridDetector", "No detector backend available")
            false
        } catch (e: Exception) {
            Log.e("HybridDetector", "Initialization failed", e)
            false
        }
    }
    
    /**
     * Detect fingers in the given bitmap
     * @param bitmap Input image bitmap
     * @return List of detection boxes
     */
    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val detector = currentDetector ?: return emptyList()
        
        return try {
            val detections = detector.detect(bitmap)
            
            // Add to history for auto-capture logic
            addToHistory(detections)
            
            detections
        } catch (e: Exception) {
            Log.e("HybridDetector", "Detection failed", e)
            emptyList()
        }
    }
    
    /**
     * Check if auto-capture should trigger
     * @param overlayBounds The D-shape overlay bounds
     * @return true if auto-capture should trigger
     */
    fun shouldAutoCapture(overlayBounds: android.graphics.RectF): Boolean {
        Log.d("HybridDetector", "Stability gate check: historySize=${detectionHistory.size}")
        if (detectionHistory.size < 5) {
            Log.d("HybridDetector", "Stability gate: need >=5 frames for reliable detection")
            return false
        }

        val recentDetections = detectionHistory.takeLast(5)
        if (recentDetections.any { it.detectionBoxes.isEmpty() }) {
            Log.d("HybridDetector", "Stability gate: one of last 5 frames has 0 detections")
            return false
        }

        // Candidate selection: pick detection per frame that best matches previous frame by IoU,
        // else fall back to highest confidence for the first frame
        fun iou(a: android.graphics.RectF, b: android.graphics.RectF): Float {
            val left = max(a.left, b.left)
            val topY = max(a.top, b.top)
            val right = min(a.right, b.right)
            val bottom = min(a.bottom, b.bottom)
            if (right <= left || bottom <= topY) return 0f
            val inter = (right - left) * (bottom - topY)
            val areaA = (a.right - a.left) * (a.bottom - a.top)
            val areaB = (b.right - b.left) * (b.bottom - b.top)
            return inter / (areaA + areaB - inter)
        }

        val selected = mutableListOf<DetectionBox>()
        // First frame: take highest confidence
        val first = recentDetections[0].detectionBoxes.maxByOrNull { it.confidence }
        if (first == null) return false
        selected.add(first)
        // Next frames: choose by max IoU with previous selected
        for (idx in 1 until recentDetections.size) {
            val prev = selected.last().boundingBox
            val candidates = recentDetections[idx].detectionBoxes
            val best = candidates.maxByOrNull { iou(prev, it.boundingBox) } ?: candidates.maxByOrNull { it.confidence }
            if (best == null) return false
            selected.add(best)
        }
        if (selected.size < 5) {
            Log.d("HybridDetector", "Stability gate: could not pick top detection for all 5 frames")
            return false
        }

        selected.forEachIndexed { idx, d ->
            Log.d(
                "HybridDetector",
                "Frame-${idx + 1}: conf=${(d.confidence * 100).toInt()}% box=${d.boundingBox}"
            )
        }

        // ROI alignment: use center-in-ROI (with +3% horizontal inflation) like detector
        if (selected.any { !isFingerCenterInsideOverlay(it, overlayBounds) }) {
            Log.d("HybridDetector", "Stability gate: a frame is outside overlay")
            return false
        }

        fun area(box: android.graphics.RectF): Float = (box.right - box.left) * (box.bottom - box.top)

        for (i in 1 until selected.size) {
            val prev = selected[i - 1].boundingBox
            val curr = selected[i].boundingBox
            val iouVal = iou(prev, curr)
            val areaPrev = area(prev)
            val areaCurr = area(curr)
            val sizeRatio = areaCurr / areaPrev
            val sizeStable = sizeRatio in 0.7f..1.3f
            Log.d(
                "HybridDetector",
                "Stability pair ${i}: IoU=${String.format("%.2f", iouVal)} sizeRatio=${String.format("%.2f", sizeRatio)} pass=${iouVal >= 0.5f && sizeStable}"
            )
            if (iouVal < 0.5f || !sizeStable) {
                Log.d("HybridDetector", "Stability gate: FAILED on pair ${i}")
                return false
            }
        }
        Log.d("HybridDetector", "Stability gate: PASSED (5 frames stable)")
        return true
    }
    
    /**
     * Check if finger is fully inside the overlay
     */
    private fun isFingerCenterInsideOverlay(detection: DetectionBox, overlayBounds: android.graphics.RectF): Boolean {
        val box = detection.boundingBox
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f
        // Horizontal +3% inflation (each side 1.5%) to match detector leniency
        val inflate = (overlayBounds.right - overlayBounds.left) * 0.015f
        val left = overlayBounds.left - inflate
        val right = overlayBounds.right + inflate
        val top = overlayBounds.top
        val bottom = overlayBounds.bottom
        return centerX in left..right && centerY in top..bottom
    }

    /**
     * Add detection results to history
     */
    fun addToHistory(detections: List<DetectionBox>) {
        detectionHistory.add(DetectionHistory(detections))
        
        // Keep only recent history
        if (detectionHistory.size > maxHistorySize) {
            detectionHistory.removeAt(0)
        }
    }
    
    /**
     * Get current detector type
     */
    fun getDetectorType(): DetectorType = detectorType
    
    /**
     * Get detection history for analytics
     */
    fun getDetectionHistory(): List<DetectionHistory> = detectionHistory.toList()
    
    /**
     * Clean up resources
     */
    fun close() {
        currentDetector?.close()
        currentDetector = null
        detectorType = DetectorType.NONE
        detectionHistory.clear()
    }
} 