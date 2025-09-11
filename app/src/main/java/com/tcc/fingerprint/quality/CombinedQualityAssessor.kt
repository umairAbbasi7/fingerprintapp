package com.tcc.fingerprint.quality

import android.graphics.Bitmap
import android.util.Log
import com.tcc.fingerprint.detection.DetectionBox
import com.tcc.fingerprint.detection.HybridDetector

/**
 * Simplified quality assessment for finger detection
 * Aligned with TFLite-Samadhi approach
 */
class CombinedQualityAssessor(private val detector: HybridDetector) {
    
    data class QualityAssessment(
        val aiConfidence: Float,
        val fingerCount: Int,
        val isQuality: Boolean,
        val recommendation: String,
        val detectionBoxes: List<DetectionBox> = emptyList()
    )
    
    fun assessQuality(bitmap: Bitmap): QualityAssessment {
        try {
            // Get AI detections
            val detections = detector.detect(bitmap)
            val fingerCount = detections.size
            
            // Calculate AI confidence (average of all detections)
            val aiConfidence = if (detections.isNotEmpty()) {
                detections.map { it.confidence }.average().toFloat()
            } else {
                0f
            }
            
            // Simple quality assessment
            val isQuality = validateQuality(aiConfidence, fingerCount)
            val recommendation = generateRecommendation(aiConfidence, fingerCount)
            
            return QualityAssessment(
                aiConfidence = aiConfidence,
                fingerCount = fingerCount,
                isQuality = isQuality,
                recommendation = recommendation,
                detectionBoxes = detections
            )
            
        } catch (e: Exception) {
            Log.e("QualityAssessor", "Quality assessment error: ${e.message}")
            return QualityAssessment(0f, 0, false, "Error in quality assessment",detectionBoxes = emptyList())
        }
    }
    
    private fun validateQuality(aiConfidence: Float, fingerCount: Int): Boolean {
        // Accept 1-10 fingers (like Samadhi)
        // For fingerprint capture, we need 1-10 distinct finger positions
        val hasReasonableFingerCount = fingerCount >= 1 && fingerCount <= 10  // 1-10 fingers acceptable
        val hasModerateConfidence = aiConfidence >= 0.25f  // 20%+ average confidence (like Samadhi)
        
        return hasReasonableFingerCount && hasModerateConfidence
    }
    
    private fun generateRecommendation(aiConfidence: Float, fingerCount: Int): String {
        return when {
            fingerCount == 0 -> "No fingers detected. Please place your fingers in the D-shape area."
            fingerCount < 1 -> "Detected $fingerCount fingers. Please place at least 1 finger in the D-shape."
            fingerCount > 10 -> "Too many fingers detected. Please use 1-10 fingers."
            aiConfidence < 0.2f -> "Low confidence (${(aiConfidence * 100).toInt()}%). Please adjust finger position for better quality."
            else -> "Perfect! $fingerCount fingers detected with ${(aiConfidence * 100).toInt()}% confidence. Ready for capture."
        }
    }
} 