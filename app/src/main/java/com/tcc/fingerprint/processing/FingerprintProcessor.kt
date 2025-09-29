package com.tcc.fingerprint.processing

import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import com.tcc.fingerprint.utils.Constants
import java.io.File
import java.io.FileOutputStream
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap

object FingerprintProcessor {
    private const val TAG = "FingerprintProcessor"

    // Simple processing configuration
    private const val JPEG_QUALITY = 95

    data class Metrics(
        val laplacianVariance: Double,
        val localContrastStdDev: Double,
        val glareRatio: Double
    )

    data class ProcessingOptions(
        val enableMetrics: Boolean = true,
        val enableEnhancement: Boolean = false,
        val claheClipLimit: Double = 1.6,
        val claheTileGrid: Int = 10,
        val unsharpSigma: Double = 1.2,
        val unsharpAmount: Double = 0.4
    )

    data class ProcessingResult(
        val processedBitmap: Bitmap,
        val originalBitmap: Bitmap,
        val qualityScore: Double,
        val isAcceptable: Boolean,
        val recommendation: String,
        val metrics: Metrics? = null
    )

    /**
     * Simple processing without complex OpenCV operations
     * This matches the successful approach from the reference project
     */
    fun processFingerprint(
        inputBitmap: Bitmap,
        roiBounds: android.graphics.RectF? = null,
        options: ProcessingOptions = ProcessingOptions(enableMetrics = false, enableEnhancement = false)
    ): ProcessingResult? {
        return try {
            Log.d(TAG, "Processing input: ${inputBitmap.width}x${inputBitmap.height}")

            // Simple ROI extraction
            val roiCropped = if (roiBounds != null) {
                cropToROI(inputBitmap, roiBounds)
            } else {
                inputBitmap
            }

            var workingBitmap = roiCropped
            var metrics: Metrics? = null

            if (options.enableMetrics || options.enableEnhancement) {
                var srcMat: Mat? = null
                var gray: Mat? = null
                var enhancedGray: Mat? = null
                var outMat: Mat? = null
                
                try {
                    srcMat = Mat()
                    Utils.bitmapToMat(roiCropped, srcMat)

                    // Convert to grayscale for metrics/enhancement
                    gray = Mat()
                    if (srcMat.channels() == 3 || srcMat.channels() == 4) {
                        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)
                    } else {
                        gray.assignTo(gray)
                        srcMat.copyTo(gray)
                    }

                    // Phase 0: metrics only
                    if (options.enableMetrics) {
                        metrics = computeMetrics(gray)
                        Log.d(TAG, "Metrics: lapVar=${metrics.laplacianVariance}, contrast=${metrics.localContrastStdDev}, glare=${(metrics.glareRatio * 100).toInt()}%")
                    }

                    // Phase 1: light enhancement - keep original color image
                    if (options.enableEnhancement) {
                        // Apply enhancement only to grayscale for metrics, but keep original color
                        enhancedGray = applyEnhancement(gray, options)
                        Log.d(TAG, "Enhancement applied to grayscale for quality metrics, maintaining original color image")
                        // Keep the original color bitmap as working bitmap
                        workingBitmap = roiCropped
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OpenCV processing skipped: ${e.message}")
                } finally {
                    // FIXED: Proper resource cleanup to prevent memory leaks
                    srcMat?.release()
                    gray?.release()
                    enhancedGray?.release()
                    outMat?.release()
                }
            }

            // Basic quality assessment (existing)
            val qualityScore = assessBasicQuality(workingBitmap)
            
            // Simple acceptance criteria
            val isAcceptable = qualityScore > 0.3 // 30% threshold
            
            val recommendation = generateSimpleRecommendation(qualityScore)

            Log.d(TAG, "Processing completed successfully")
            Log.d(TAG, "Quality score: ${qualityScore * 100}%")
            Log.d(TAG, "Is acceptable: $isAcceptable")
            Log.d(TAG, "Recommendation: $recommendation")

            ProcessingResult(
                processedBitmap = workingBitmap,
                originalBitmap = roiCropped,
                qualityScore = qualityScore,
                isAcceptable = isAcceptable,
                recommendation = recommendation,
                metrics = metrics
            )

        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}")
            null
        }
    }

    private fun computeMetrics(gray: Mat): Metrics {
        var lap: Mat? = null
        var mean: MatOfDouble? = null
        var std: MatOfDouble? = null
        var mean2: MatOfDouble? = null
        var std2: MatOfDouble? = null
        var thresh: Mat? = null
        
        try {
            // Laplacian variance
            lap = Mat()
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            mean = MatOfDouble()
            std = MatOfDouble()
            org.opencv.core.Core.meanStdDev(lap, mean, std)
            val lapVar = std.toArray().firstOrNull() ?: 0.0

            // Local contrast (stddev of gray)
            mean2 = MatOfDouble()
            std2 = MatOfDouble()
            org.opencv.core.Core.meanStdDev(gray, mean2, std2)
            val contrastStd = std2.toArray().firstOrNull() ?: 0.0

            // Glare ratio (pixels > 250)
            thresh = Mat()
            Imgproc.threshold(gray, thresh, 250.0, 255.0, Imgproc.THRESH_BINARY)
            val white = org.opencv.core.Core.countNonZero(thresh)
            val glareRatio = if (gray.rows() > 0 && gray.cols() > 0) white.toDouble() / (gray.rows().toDouble() * gray.cols().toDouble()) else 0.0

            return Metrics(laplacianVariance = lapVar, localContrastStdDev = contrastStd, glareRatio = glareRatio)
        } finally {
            // FIXED: Proper resource cleanup to prevent memory leaks
            lap?.release()
            mean?.release()
            std?.release()
            mean2?.release()
            std2?.release()
            thresh?.release()
        }
    }

    private fun applyEnhancement(gray: Mat, options: ProcessingOptions): Mat {
        var claheOut: Mat? = null
        var blurred: Mat? = null
        var sharp: Mat? = null
        
        try {
            // Step 1: Gentle CLAHE to avoid over-brightening
            val clahe = Imgproc.createCLAHE(options.claheClipLimit, Size(options.claheTileGrid.toDouble(), options.claheTileGrid.toDouble()))
            claheOut = Mat()
            clahe.apply(gray, claheOut)
            
            // Step 2: Standard unsharp mask without aggressive edge enhancement
            blurred = Mat()
            Imgproc.GaussianBlur(claheOut, blurred, Size(0.0, 0.0), options.unsharpSigma)
            sharp = Mat()
            
            // Gentle unsharp mask to avoid whitening effect
            Core.addWeighted(claheOut, 1.0 + options.unsharpAmount, blurred, -options.unsharpAmount, 0.0, sharp)
            
            Log.d(TAG, "Applied balanced sharpening without whitening - clipLimit: ${options.claheClipLimit}, unsharpAmount: ${options.unsharpAmount}")
            
            return sharp!!
        } finally {
            // FIXED: Clean up intermediate Mats, but keep the final result
            claheOut?.release()
            blurred?.release()
            // Note: sharp is returned, so don't release it here
        }
    }

    /**
     * Simple region of interest (ROI) cropping that does not rely on OpenCV dependencies.
     * Inspired by approaches proven in earlier projects.
     */
    private fun cropToROI(bitmap: Bitmap, roiBounds: android.graphics.RectF): Bitmap {
        return try {
            val left = roiBounds.left.toInt().coerceAtLeast(0)
            val top = roiBounds.top.toInt().coerceAtLeast(0)
            val right = roiBounds.right.toInt().coerceAtMost(bitmap.width)
            val bottom = roiBounds.bottom.toInt().coerceAtMost(bitmap.height)

            val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            
            Log.d(TAG, "ROI cropping: ${croppedBitmap.width}x${croppedBitmap.height}")
            croppedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "ROI cropping failed: ${e.message}")
            bitmap // Return original if cropping fails
        }
    }

    /**
     * Efficient basic quality assessment without the use of OpenCV routines.
     * Mimics fast and robust procedures from field-proven desktop versions.
     */
    private fun assessBasicQuality(bitmap: Bitmap): Double {
        return try {
            // Perform a simple, fast brightness and contrast assessment using pixel data
            val brightness = assessBrightness(bitmap)
            val contrast = assessContrast(bitmap)
            
            // Compute a weighted quality score for practical acceptance
            val quality = (brightness * 0.5 + contrast * 0.5)
            
            quality.coerceIn(0.0, 1.0)

        } catch (e: Exception) {
            Log.e(TAG, "Basic quality assessment failed: ${e.message}")
            0.5 // Default to 50% if assessment fails
        }
    }

    fun isImageQualityAcceptable(bitmap: Bitmap): Pair<Boolean, String> {
        var srcMat: Mat? = null
        var gray: Mat? = null
        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            gray = Mat()
            if (srcMat.channels() == 3 || srcMat.channels() == 4) {
                Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                srcMat.copyTo(gray)
            }

            val metrics = computeMetrics(gray)

            val notBlurry = metrics.laplacianVariance > Constants.BLUR_THRESHOLD
            val goodContrast = metrics.localContrastStdDev > Constants.CONTRAST_THRESHOLD
            val lowGlare = metrics.glareRatio < Constants.GLARE_THRESHOLD

            if (!notBlurry) return false to "Image is blurry - hold steady"
            if (!goodContrast) return false to "Low contrast - fingerprints not clear, adjust lighting or position"
            if (!lowGlare) return false to "Too much glare - reduce light reflection"

            return true to "Good image quality"
        } catch (e: Exception) {
            Log.e(TAG, "Image quality check failed: ${e.message}")
            return false to "Quality check error - please try again"
        } finally {
            srcMat?.release()
            gray?.release()
        }
    }

    /**
     * Quickly estimates image brightness for quality scoring by averaging pixel values.
     * Designed to deliver practical results in real-world scenarios.
     */
    private fun assessBrightness(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var totalBrightness = 0.0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalBrightness += (r + g + b) / 3.0
        }

        val averageBrightness = totalBrightness / pixels.size / 255.0
        
        // Target optimal brightness is 0.5; penalize deviation for robust scoring
        val optimalBrightness = 0.5
        val brightnessScore = 1.0 - kotlin.math.abs(averageBrightness - optimalBrightness) * 2.0
        
        return brightnessScore.coerceIn(0.0, 1.0)
    }

    /**
     * Quickly evaluates contrast using the standard deviation of brightness values,
     * offering a reliable metric for fingerprint image quality.
     */
    private fun assessContrast(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var totalBrightness = 0.0
        var totalSquaredBrightness = 0.0

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = (r + g + b) / 3.0
            totalBrightness += brightness
            totalSquaredBrightness += brightness * brightness
        }

        val mean = totalBrightness / pixels.size
        val variance = (totalSquaredBrightness / pixels.size) - (mean * mean)
        val stdDev = kotlin.math.sqrt(variance)

        val contrast = stdDev / 255.0
        return contrast.coerceIn(0.0, 1.0)
    }

    /**
     * Generates a user recommendation based on quality score calculated
     * by the basic assessment routines.
     */
    private fun generateSimpleRecommendation(qualityScore: Double): String {
        return when {
            qualityScore > 0.7 -> "Excellent quality - Ready for processing"
            qualityScore > 0.5 -> "Good quality - Acceptable for processing"
            qualityScore > 0.3 -> "Fair quality - May need improvement"
            else -> "Poor quality - Retake image"
        }
    }

    /**
     * Saves the fingerprint image using high JPEG quality to maximize data retention.
     * Follows best practices observed in proven fingerprint capture apps.
     */
    fun saveProcessedImage(bitmap: Bitmap, filePath: String): Boolean {
        return try {
            Log.d(TAG, "Saving with high JPEG quality")
            
            val file = File(filePath)
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }

            val outputStream = FileOutputStream(file)
            // Use high JPEG quality as a default for important data
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            outputStream.close()

            if (success) {
                Log.d(TAG, "Image saved successfully: ${file.absolutePath}")
                Log.d(TAG, "File size: ${file.length()} bytes")
            } else {
                Log.e(TAG, "Bitmap compression failed")
            }

            success

        } catch (e: Exception) {
            Log.e(TAG, "Error saving image: ${e.message}")
            false
        }
    }

    /**
     * Saves image directly to the gallery using high JPEG quality, ensuring
     * accessibility and usability by third-party apps or users.
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val filename = "Fingerprint_${System.currentTimeMillis()}.jpg"

            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Fingerprints")
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let { imageUri ->
                context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    // Maintain high JPEG quality, suitable for archiving fingerprint samples
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
                }
            }

            Log.d(TAG, "Image saved to gallery: $filename")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to gallery: ${e.message}")
            false
        }
    }
} 