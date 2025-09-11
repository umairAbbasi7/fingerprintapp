package com.tcc.fingerprint.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX Runtime implementation of FingerDetector as a fallback backend.
 * Assumes single-class YOLO-style output [1, 300, 6] with (cx, cy, w, h, obj, prob)
 * and same pre/post processing as TFLiteDetector.
 */
class OnnxDetector(private val context: Context) : FingerDetector {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val modelFile = "best32.onnx"
    private val inputSize = 640
    private val confidenceThreshold = 0.3f
    private val enablePinkyAssist = true
    private val nmsThreshold = if (enablePinkyAssist) 0.65f else 0.5f

    override fun isSupported(): Boolean = true

    override fun initialize(): Boolean {
        return try {
            val modelPath = File(context.getExternalFilesDir(null), modelFile)
            if (!modelPath.exists()) {
                context.assets.open(modelFile).use { input ->
                    modelPath.outputStream().use { output -> input.copyTo(output) }
                }
            }
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelPath.absolutePath, OrtSession.SessionOptions())
            Log.d("OnnxDetector", "ONNX initialized")
            true
        } catch (e: Exception) {
            Log.e("OnnxDetector", "ONNX init failed: ${e.message}")
            false
        }
    }

    override fun detect(bitmap: Bitmap): List<DetectionBox> {
        val sess = session ?: return emptyList()
        return try {
            val (inputTensor, scaleX, scaleY) = preprocess(bitmap)
            val outputs = sess.run(mapOf(sess.inputNames.iterator().next() to inputTensor))
            val outputArr = (outputs[0].value as Array<FloatArrayArray>).flattenToFloatArray()
            val raw = decodeDetections(outputArr, 300, 6, bitmap.width, bitmap.height, scaleX, scaleY)

            // Apply same ROI and shape filters as TFLite
            var safetyFiltered = 0
            val filtered = mutableListOf<DetectionBox>()
            for (det in raw) {
                if (isValidFingerPosition(det.boundingBox, bitmap.width, bitmap.height)) {
                    if (isValidFingerShape(det.boundingBox, bitmap.width, bitmap.height)) {
                        Log.d("OnnxDetector", "Finger detected: Confidence ${(det.confidence * 100).toInt()}%")
                        filtered.add(det)
                    } else {
                        Log.d("OnnxDetector", "Shape rejected: Invalid finger shape")
                        safetyFiltered++
                    }
                } else {
                    Log.d("OnnxDetector", "Position rejected: Outside D-shape overlay")
                    safetyFiltered++
                }
            }

            Log.d("OnnxDetector", "DIAGNOSTIC SUMMARY:")
            Log.d("OnnxDetector", "  - Detections found (pre-NMS): ${filtered.size}")
            Log.d("OnnxDetector", "  - Safety filtered: $safetyFiltered")

            val detections = applyNMS(filtered)
            outputs.close()
            inputTensor.close()
            applyNMS(detections)
        } catch (e: Exception) {
            Log.e("OnnxDetector", "Detection failed: ${e.message}")
            emptyList()
        }
    }

    private fun preprocess(bitmap: Bitmap): Triple<OnnxTensor, Float, Float> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val floatBuffer = FloatBuffer.allocate(inputSize * inputSize * 3)
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = resized.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                floatBuffer.put(r)
                floatBuffer.put(g)
                floatBuffer.put(b)
            }
        }
        floatBuffer.rewind()
        val tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), floatBuffer, longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3))
        val scaleX = bitmap.width.toFloat() / inputSize
        val scaleY = bitmap.height.toFloat() / inputSize
        return Triple(tensor, scaleX, scaleY)
    }

    private fun decodeDetections(
        output: FloatArray,
        maxDet: Int,
        stride: Int,
        origW: Int,
        origH: Int,
        scaleX: Float,
        scaleY: Float
    ): MutableList<DetectionBox> {
        val list = mutableListOf<DetectionBox>()
        for (i in 0 until maxDet) {
            val base = i * stride
            val cx = output[base + 0] * inputSize * scaleX
            val cy = output[base + 1] * inputSize * scaleY
            val w = output[base + 2] * inputSize * scaleX
            val h = output[base + 3] * inputSize * scaleY
            val obj = output[base + 4]
            val prob = output[base + 5]
            val score = obj // using objectness like current TFLite path
            if (score < confidenceThreshold) continue
            val x1 = max(0f, cx - w / 2f)
            val y1 = max(0f, cy - h / 2f)
            val x2 = min(origW.toFloat(), cx + w / 2f)
            val y2 = min(origH.toFloat(), cy + h / 2f)
            list.add(DetectionBox(RectF(x1, y1, x2, y2), score, 0))
        }
        return list
    }

    private fun applyNMS(detections: List<DetectionBox>): List<DetectionBox> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<DetectionBox>()
        val suppressed = BooleanArray(sorted.size)

        // Pinky assist: protect the smallest width candidate
        var protectedIndex: Int? = null
        if (enablePinkyAssist && sorted.size >= 3) {
            protectedIndex = sorted.withIndex().minByOrNull { it.value.width }?.index
        }

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            keep.add(a)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (protectedIndex != null && j == protectedIndex) continue
                val b = sorted[j]
                if (iou(a.boundingBox, b.boundingBox) > nmsThreshold) suppressed[j] = true
            }
        }
        return keep
    }

    private fun isValidFingerPosition(boundingBox: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        val widthFactor = 0.95f
        val heightFactor = 0.55f
        val roiWidth = imageWidth * widthFactor
        val roiHeight = imageHeight * heightFactor
        var left = (imageWidth - roiWidth) / 2
        var top = (imageHeight - roiHeight) / 2
        var right = left + roiWidth
        var bottom = top + roiHeight

        // +3% horizontal inflation for tolerance
        val horizontalInflate = imageWidth * 0.015f
        left = max(0f, left - horizontalInflate)
        right = min(imageWidth.toFloat(), right + horizontalInflate)

        val detectionCenterX = (boundingBox.left + boundingBox.right) / 2
        val detectionCenterY = (boundingBox.top + boundingBox.bottom) / 2

        Log.d("OnnxDetector", "D-shape check: center=(${detectionCenterX.toInt()},${detectionCenterY.toInt()}), bounds=(${left.toInt()},${top.toInt()},${bottom.toInt()})")

        val centerInside = detectionCenterX in left..right && detectionCenterY in top..bottom
        if (!centerInside) {
            Log.d("OnnxDetector", "Position rejected: outside D-shape overlay")
            return false
        }
        Log.d("OnnxDetector", "Position accepted: inside D-shape overlay")
        return true
    }

    private fun isValidFingerShape(boundingBox: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        val width = boundingBox.right - boundingBox.left
        val height = boundingBox.bottom - boundingBox.top

        val minSize = min(imageWidth, imageHeight) * 0.05f
        val maxBoxWidth = imageWidth * 0.95f
        val maxBoxHeight = imageHeight * 0.95f

        if (width < minSize || height < minSize) {
            Log.d("OnnxDetector", "Shape rejected: Too small (${width.toInt()}x${height.toInt()})")
            return false
        }
        if (width > maxBoxWidth || height > maxBoxHeight) {
            Log.d("OnnxDetector", "Shape rejected: Too large (${width.toInt()}x${height.toInt()}) - max allowed: ${maxBoxWidth.toInt()}x${maxBoxHeight.toInt()})")
            return false
        }

        val aspectRatio = width / height
        val isSmallOrSkinny = (width < imageWidth * 0.25f) || (height < imageHeight * 0.25f)
        val aspectMin = if (enablePinkyAssist && isSmallOrSkinny) 0.3f else 0.1f
        val aspectMax = if (enablePinkyAssist && isSmallOrSkinny) 3.5f else 5.0f
        val isAspectValid = aspectRatio in aspectMin..aspectMax
        if (!isAspectValid) {
            Log.d("OnnxDetector", "Shape rejected: Invalid aspect ratio ${aspectRatio.toFloat()} (should be ${aspectMin}-${aspectMax})")
            return false
        }

        val area = width * height
        val imageArea = imageWidth * imageHeight
        val areaRatio = area / imageArea
        val minAreaRatio = if (enablePinkyAssist) 0.001f else 0.002f
        if (areaRatio < minAreaRatio || areaRatio > 0.60f) {
            Log.d("OnnxDetector", "Shape rejected: Invalid area ratio ${(areaRatio * 100).toInt()}% (should be ${(minAreaRatio*100)}-60%)")
            return false
        }

        Log.d("OnnxDetector", "Shape accepted: ${width.toInt()}x${height.toInt()}, aspect=${aspectRatio.toFloat()}, area=${(areaRatio * 100).toInt()}%")
        return true
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val inter = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter)
    }

    override fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        env = null
    }
}

// Helper to flatten ONNX output if it returns nested arrays
typealias FloatArrayArray = Array<FloatArray>

private fun Array<FloatArrayArray>.flattenToFloatArray(): FloatArray {
    val outer = this
    if (outer.isEmpty()) return floatArrayOf()
    // Assuming shape [1][300][6]
    val dets = outer[0]
    val total = dets.size * dets[0].size
    val out = FloatArray(total)
    var k = 0
    for (i in dets.indices) {
        val row = dets[i]
        for (j in row.indices) out[k++] = row[j]
    }
    return out
}

