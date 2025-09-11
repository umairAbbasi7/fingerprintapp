package com.tcc.fingerprint.detection

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Abstract interface for finger detection implementations
 * Supports both TFLite and ONNX runtime backends
 */
interface FingerDetector {
    
    /**
     * Detect fingers in the given bitmap
     * @param bitmap Input image bitmap
     * @return List of detection boxes with confidence scores
     */
    fun detect(bitmap: Bitmap): List<DetectionBox>
    
    /**
     * Check if this detector is supported on current device
     * @return true if supported, false otherwise
     */
    fun isSupported(): Boolean
    
    /**
     * Initialize the detector
     * @return true if initialization successful
     */
    fun initialize(): Boolean
    
    /**
     * Clean up resources
     */
    fun close()
}

/**
 * Data class representing a detection box
 */
data class DetectionBox(
    val boundingBox: RectF,      // [x1, y1, x2, y2] in pixel coordinates
    val confidence: Float,        // Confidence score 0.0-1.0
    val classId: Int = 0         // Class ID (0 for finger)
) {
    val centerX: Float get() = (boundingBox.left + boundingBox.right) / 2f
    val centerY: Float get() = (boundingBox.top + boundingBox.bottom) / 2f
    val width: Float get() = boundingBox.right - boundingBox.left
    val height: Float get() = boundingBox.bottom - boundingBox.top
}



/**
 * Detection history for auto-capture logic
 */
data class DetectionHistory(
    val detectionBoxes: List<DetectionBox>,
    val timestamp: Long = System.currentTimeMillis()
) 