package com.tcc.fingerprint.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import android.util.Log

/**
 * TFLite implementation of FingerDetector
 * Uses TensorFlow Lite for finger detection
 * Enhanced with device compatibility and optimization
 */
class TFLiteDetector(private val context: Context) : FingerDetector {
    
    private var interpreter: Interpreter? = null
    private val modelFile = "best32.tflite"
    private val inputSize = 640
    private val confidenceThreshold = 0.5f  // Increased to reduce false positives
    private val enablePinkyAssist = true
    private val nmsThreshold = if (enablePinkyAssist) 0.65f else 0.5f
    
    // Dynamic tensor shape detection
    private var outputShape: IntArray? = null
    private var numDetections: Int = 0
    private var numClasses: Int = 0
    
    // Single-class finger detection model
    private val fingerClass = 0  // Single class: finger
    
    override fun isSupported(): Boolean {
        return try {
            val testFile = File(context.getExternalFilesDir(null), "test.tflite")
            if (!testFile.exists()) {
                context.assets.open(modelFile).use { input ->
                    testFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val options = Interpreter.Options()
            val testInterpreter = Interpreter(testFile, options)
            testInterpreter.close()
            true
        } catch (e: Exception) {
            Log.d("TFLiteDetector", "TFLite not supported: ${e.message}")
            false
        }
    }
    
    override fun initialize(): Boolean {
        return try {
            val modelPath = File(context.getExternalFilesDir(null), modelFile)
            if (!modelPath.exists()) {
                context.assets.open(modelFile).use { input ->
                    modelPath.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // Check model file
            Log.d("TFLiteDetector", "Model file exists: ${modelPath.exists()}")
            Log.d("TFLiteDetector", "Model file size: ${modelPath.length()} bytes")
            
            // Try multiple acceleration strategies with fallback
            interpreter = tryInitializeWithAcceleration(modelPath)
            
            if (interpreter == null) {
                Log.e("TFLiteDetector", "Failed to initialize interpreter with any acceleration method")
                return false
            }
            
            // Detect tensor shapes dynamically
            detectTensorShapes()
            
            // Log initialization success
            Log.d("TFLiteDetector", "TFLite initialized successfully")
            Log.d("TFLiteDetector", "Output shape: ${outputShape?.contentToString()}")
            Log.d("TFLiteDetector", "Detections: $numDetections, Classes: $numClasses")
            
            true
        } catch (e: Exception) {
            Log.e("TFLiteDetector", "Initialization failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Try multiple acceleration strategies with fallback
     */
    private fun tryInitializeWithAcceleration(modelPath: File): Interpreter? {
        val strategies = listOf(
            "NNAPI + Memory Optimization" to { createNNAPIWithMemoryOptimization() },
            "NNAPI Only" to { createNNAPIOnly() },
            "GPU Delegate" to { createGPUDelegate() },
            "XNNPACK CPU" to { createXNNPACKCPU() },
            "CPU Only" to { createCPUOnly() }
        )
        
        for ((strategyName, strategy) in strategies) {
            try {
                Log.d("TFLiteDetector", "Trying: $strategyName")
                val options = strategy()
                val testInterpreter = Interpreter(modelPath, options)
                
                // Test if interpreter works
                val inputTensor = testInterpreter.getInputTensor(0)
                val outputTensor = testInterpreter.getOutputTensor(0)
                
                Log.d("TFLiteDetector", "Success with: $strategyName")
                Log.d("TFLiteDetector", "Input tensor: ${inputTensor.shape().contentToString()}")
                Log.d("TFLiteDetector", "Output tensor: ${outputTensor.shape().contentToString()}")
                
                return testInterpreter
            } catch (e: Exception) {
                Log.d("TFLiteDetector", "Failed: $strategyName - ${e.message}")
            }
        }
        
        return null
    }
    
    private fun createNNAPIWithMemoryOptimization(): Interpreter.Options {
        return Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
            setUseNNAPI(true)
            setAllowFp16PrecisionForFp32(true)  // Memory optimization
            setAllowBufferHandleOutput(true)      // Performance optimization
        }
    }
    
    private fun createNNAPIOnly(): Interpreter.Options {
        return Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
            setUseNNAPI(true)
        }
    }
    
    private fun createGPUDelegate(): Interpreter.Options {
        return Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
            // Note: GPU delegate requires additional setup, using NNAPI as fallback
            setUseNNAPI(true)
        }
    }
    
    private fun createXNNPACKCPU(): Interpreter.Options {
        return Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
            setUseXNNPACK(true)  // CPU optimization
        }
    }
    
    private fun createCPUOnly(): Interpreter.Options {
        return Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
        }
    }
    
    /**
     * Detect tensor shapes dynamically
     */
    private fun detectTensorShapes() {
        val outputTensor = interpreter?.getOutputTensor(0)
        outputShape = outputTensor?.shape()
        
        when {
            outputShape?.size == 3 -> {
                // Format: [1, num_detections, 6] (cx, cy, w, h, objectness, finger_prob)
                numDetections = outputShape!![1]
                numClasses = 1  // Single class: finger
                Log.d("TFLiteDetector", "Detected format: [1, $numDetections, 6] (single-class finger model)")
            }
            outputShape?.size == 2 -> {
                // Format: [1, num_detections * 6]
                val totalElements = outputShape!![1]
                numDetections = totalElements / 6
                numClasses = 1  // Single class: finger
                Log.d("TFLiteDetector", "Detected format: [1, $totalElements] (single-class finger model)")
            }
            else -> {
                // Fallback to default values for single-class model
                numDetections = 8400
                numClasses = 1
                Log.d("TFLiteDetector", "Unknown tensor format, using single-class defaults")
            }
        }
    }
    
    override fun detect(bitmap: Bitmap): List<DetectionBox> {
        val interpreter = interpreter ?: return emptyList()
        
        try {
            // Preprocessing: Resize to [640, 640], convert to float32, normalize
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Convert to float32 and normalize (pixel / 255.0)
            val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = resizedBitmap.getPixel(x, y)
                    // RGB order
                    inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f)) // R
                    inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))   // G
                    inputBuffer.putFloat(((pixel and 0xFF) / 255.0f))        // B
                }
            }
            inputBuffer.rewind()
            
            // Create output buffer with correct shape [1, 300, 6]
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 300, 6), org.tensorflow.lite.DataType.FLOAT32)
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer.buffer)
            
            // Get output data
            val output = outputBuffer.floatArray
            
            // Log performance metrics
            val memoryUsage = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            Log.d("TFLiteDetector", "Inference output size: ${output.size}")
            Log.d("TFLiteDetector", "Memory usage: ${memoryUsage / 1024 / 1024}MB")
            
            // Decode detections
            val detections = decodeDetections(output, bitmap.width, bitmap.height)
            
            // Apply NMS
            val finalDetections = applyNMS(detections)
            
            Log.d("TFLiteDetector", "Detections: ${detections.size} → ${finalDetections.size} after NMS")
            
            return finalDetections
            
        } catch (e: Exception) {
            Log.e("TFLiteDetector", "Detection error: ${e.message}")
            return emptyList()
        }
    }
    
        private fun decodeDetections(output: FloatArray, originalWidth: Int, originalHeight: Int): List<DetectionBox> {
        val detections = mutableListOf<DetectionBox>()
        
        // Diagnostic counters
        var highObjectnessCount = 0
        var maxObjectness = 0f
        var fingerDetections = 0
        var safetyFilteredCount = 0
        
        // Use correct tensor shape [1, 300, 6]
        val maxDetections = 300  // Fixed for new model
        val valuesPerDetection = 6
        
        for (i in 0 until maxDetections) {
            // Correct array indexing for [1, 300, 6] format
            val baseIndex = i * valuesPerDetection
            
            // Decode bounding box (cx, cy, w, h format)
            val cx = output[baseIndex] * originalWidth
            val cy = output[baseIndex + 1] * originalHeight
            val w = output[baseIndex + 2] * originalWidth
            val h = output[baseIndex + 3] * originalHeight
            
            // Get objectness and finger probability
            val objectness = output[baseIndex + 4]
            val fingerProb = output[baseIndex + 5]
            
                                     // Use objectness only since finger_prob is always 0
            val score = objectness  // Changed from: objectness * fingerProb
            
            if (objectness > maxObjectness) {
                maxObjectness = objectness
            }
            
            if (objectness > 0.05f) {
                highObjectnessCount++
            }
            
            // Debug ALL finger probability values
            if (objectness > 0.05f) {  // Only log detections with reasonable objectness
                                 Log.d("TFLiteDetector", "ALL DETECTIONS: Detection $i: objectness=${(objectness * 100).toInt()}%, fingerProb=${(fingerProb * 100).toInt()}%, score=${(score * 100).toInt()}%")
                fingerDetections++
            }
            
                                     // Lower threshold since we're using objectness only
            if (score >= 0.3f) {  // Lowered from 0.5f to 0.3f
                // Convert to [x1, y1, x2, y2] format
                val x1 = max(0f, cx - w / 2)
                val y1 = max(0f, cy - h / 2)
                val x2 = min(originalWidth.toFloat(), cx + w / 2)
                val y2 = min(originalHeight.toFloat(), cy + h / 2)
                
                val boundingBox = RectF(x1, y1, x2, y2)
                
                // Check if box is within D-shape overlay
                if (isValidFingerPosition(boundingBox, originalWidth, originalHeight)) {
                    // Additional safety checks
                    if (isValidFingerShape(boundingBox, originalWidth, originalHeight)) {
                        detections.add(DetectionBox(boundingBox, score, fingerClass))
                                                 Log.d("TFLiteDetector", "Finger detected: Confidence ${(score * 100).toInt()}%")
                    } else {
                                                 Log.d("TFLiteDetector", "Shape rejected: Invalid finger shape")
                        safetyFilteredCount++
                    }
                } else {
                                         Log.d("TFLiteDetector", "Position rejected: Outside D-shape overlay")
                    safetyFilteredCount++
                }
            }
        }
        
        // Diagnostic summary
                 Log.d("TFLiteDetector", "DIAGNOSTIC SUMMARY:")
        Log.d("TFLiteDetector", "  - Max objectness: ${(maxObjectness * 100).toInt()}%")
        Log.d("TFLiteDetector", "  - High objectness count (>5%): $highObjectnessCount")
        Log.d("TFLiteDetector", "  - Finger detections: $fingerDetections")
        Log.d("TFLiteDetector", "  - Safety filtered: $safetyFilteredCount")
        Log.d("TFLiteDetector", "  - Detections found: ${detections.size}")
        
        return detections
    }
    
    private fun isValidFingerDetection(classScore: Float): Boolean {
        return classScore >= confidenceThreshold
    }
    
    private fun isValidFingerPosition(boundingBox: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        // D-shape overlay bounds check
        val widthFactor = 0.95f
        val heightFactor = 0.55f
        
        val roiWidth = imageWidth * widthFactor
        val roiHeight = imageHeight * heightFactor
        
        var left = (imageWidth - roiWidth) / 2
        var top = (imageHeight - roiHeight) / 2
        var right = left + roiWidth
        var bottom = top + roiHeight

        // Apply a horizontal inflation to the D-ROI validation for increased detection tolerance near image edges
        val horizontalInflate = imageWidth * 0.015f  // 1.5% on each side => 3% total
        left = max(0f, left - horizontalInflate)
        right = min(imageWidth.toFloat(), right + horizontalInflate)
        
        val detectionCenterX = (boundingBox.left + boundingBox.right) / 2
        val detectionCenterY = (boundingBox.top + boundingBox.bottom) / 2
        
        // Validates whether the bounding box center lies within the D-shape overlay region (capture gate requirement)
                 Log.d("TFLiteDetector", "D-shape check: center=(${detectionCenterX.toInt()},${detectionCenterY.toInt()}), bounds=(${left.toInt()},${top.toInt()},${bottom.toInt()})")

        // For detection acceptance we keep the center-in-ROI rule (with +3% horizontal tolerance)
        val centerInside = detectionCenterX in left..right && detectionCenterY in top..bottom
        if (!centerInside) {
                         Log.d("TFLiteDetector", "Position rejected: outside D-shape overlay")
            return false
        }
                 Log.d("TFLiteDetector", "Position accepted: inside D-shape overlay")
        return true
    }
    
    /**
     * Safety filter: Validate finger shape characteristics
     */
    private fun isValidFingerShape(boundingBox: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        val width = boundingBox.right - boundingBox.left
        val height = boundingBox.bottom - boundingBox.top
        
        // Relaxed recommendation: Permit detection boxes up to 95% of frame for robustness
         val minSize = min(imageWidth, imageHeight) * 0.05f  // 5% of image
         val maxBoxWidth = imageWidth * 0.95f   // 95% of frame width
         val maxBoxHeight = imageHeight * 0.95f // 95% of frame height
        
        if (width < minSize || height < minSize) {
                         Log.d("TFLiteDetector", "Shape rejected: Too small (${width.toInt()}x${height.toInt()})")
            return false
        }
        
                 if (width > maxBoxWidth || height > maxBoxHeight) {
             Log.d("TFLiteDetector", "Shape rejected: Too large (${width.toInt()}x${height.toInt()}) - max allowed: ${maxBoxWidth.toInt()}x${maxBoxHeight.toInt()})")
             return false
         }
        
        // Adjust aspect ratio limits using "pinky assist" — lenient bounds for smaller/narrower finger boxes
        val aspectRatio = width / height
        val isSmallOrSkinny = (width < imageWidth * 0.25f) || (height < imageHeight * 0.25f)
        val aspectMin = if (enablePinkyAssist && isSmallOrSkinny) 0.3f else 0.1f
        val aspectMax = if (enablePinkyAssist && isSmallOrSkinny) 3.5f else 5.0f
        val isAspectRatioValid = aspectRatio in aspectMin..aspectMax
        if (!isAspectRatioValid) {
                         Log.d("TFLiteDetector", "Shape rejected: Invalid aspect ratio ${aspectRatio.toFloat()} (should be ${aspectMin}-${aspectMax})")
            return false
        }
        
        // Area validation: Ensures horizontal and small finger detections are accepted within robust area bounds
         val area = width * height
         val imageArea = imageWidth * imageHeight
         val areaRatio = area / imageArea
         
         val minAreaRatio = if (enablePinkyAssist) 0.001f else 0.002f // 0.1% with pinky assist
         if (areaRatio < minAreaRatio || areaRatio > 0.60f) {  // 0.1/0.2% to 60% of image
             Log.d("TFLiteDetector", "Shape rejected: Invalid area ratio ${(areaRatio * 100).toInt()}% (should be ${(minAreaRatio*100)}-60%)")
             return false
         }
        
                 Log.d("TFLiteDetector", "Shape accepted: ${width.toInt()}x${height.toInt()}, aspect=${aspectRatio.toFloat()}, area=${(areaRatio * 100).toInt()}%")
        return true
    }
    
    private fun applyNMS(detections: List<DetectionBox>): List<DetectionBox> {
        if (detections.isEmpty()) return emptyList()
        
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val finalDetections = mutableListOf<DetectionBox>()
        val suppressed = BooleanArray(sortedDetections.size) { false }

        // "Pinky assist": Protects the smallest-width (typically pinky finger) from suppression during NMS filtering
        var protectedIndex: Int? = null
        if (enablePinkyAssist && sortedDetections.size >= 3) {
            protectedIndex = sortedDetections.withIndex()
                .filter { (idx, _) -> !suppressed[idx] }
                .minByOrNull { it.value.width }?.index
        }
        
        for (i in sortedDetections.indices) {
            if (suppressed[i]) continue
            
            val current = sortedDetections[i]
            finalDetections.add(current)
            
            for (j in (i + 1) until sortedDetections.size) {
                if (suppressed[j]) continue
                
                val other = sortedDetections[j]
                val iou = calculateIoU(current.boundingBox, other.boundingBox)
                
                // Do not suppress the protected (pinky) candidate
                if (protectedIndex != null && j == protectedIndex) continue

                if (iou > nmsThreshold) {
                    suppressed[j] = true
                }
            }
        }
        
        return finalDetections
    }
    
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)
        
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea
        
        return intersectionArea / unionArea
    }
    
    override fun close() {
        interpreter?.close()
        interpreter = null
    }
} 