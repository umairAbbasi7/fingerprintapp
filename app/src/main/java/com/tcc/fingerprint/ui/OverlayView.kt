package com.tcc.fingerprint.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt

/**
 * Custom OverlayView from FP Project
 * Features: D-shape design, enhanced visual feedback, real-time metrics
 */
import kotlin.math.min


class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs)
{

    // Content area in view coordinates where the camera preview is actually drawn
    // If null, defaults to entire view
    private var contentBounds: RectF? = null

    // Set preview content rectangle for center-crop alignment
//    fun setPreviewContentRect(rect: RectF) {
//        contentBounds = rect
//        invalidate() // Redraw with new content bounds
//    }

    // Get preview content rectangle for coordinate mapping
    fun getPreviewContentRect(): RectF? {
        return contentBounds
    }

    private var roiBounds: RectF? = null
    var roiPath: Path? = null

    // Finger Detection Visual Feedback
    private var showCaptureIndicator: Boolean = false

    // Countdown variables
    private var countdownValue: Int = 0
    private var showCountdown: Boolean = false

    // Center Message Variables
    private var showCenterMessage: Boolean = false
    private var centerMessage: String = ""

    private val roiPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    // Enhanced Visual Feedback Paints
    private val captureIndicatorPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
    }

    private val confidenceTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // Countdown paint
    private val countdownPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 80f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val messagePaint = Paint().apply {
        color = Color.GREEN
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val transparentPaint = Paint().apply {
        color = "#A6000000".toColorInt() // Semi-transparent black
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Build ROI coordinates (same logic as before)
        val content = contentBounds
        val roiLeft: Float
        val roiTop: Float
        val roiWidth: Float
        val roiHeight: Float

        if (content != null) {
            roiLeft = content.left
            roiTop = content.top
            roiWidth = content.width()
            roiHeight = content.height()
        } else {
            val widthFactor = 0.95f
            val heightFactor = 0.55f
            roiWidth = width * widthFactor
            roiHeight = height * heightFactor
            roiLeft = (width - roiWidth) / 2f
            roiTop = (height - roiHeight) / 2f
        }

        val left = roiLeft
        val top = roiTop
        val right = left + roiWidth
        val bottom = top + roiHeight
        val radius = roiHeight / 2f

        // Save roi bounds
        roiBounds = RectF(left, top, right, bottom)

        // Build D-shape path anchored to roiBounds
        roiPath = Path().apply {
            reset()
            // D-shape: left->top to arc on right to bottom->left
            moveTo(left, top)
            lineTo(right - radius, top)
            arcTo(RectF(right - 2 * radius, top, right, bottom), -90f, 180f, false)
            lineTo(left, bottom)
            close()
        }

        // --- DIM ONLY OUTSIDE ROI using EVEN_ODD fill ---
        val dimPath = Path().apply {
            reset()
            fillType = Path.FillType.EVEN_ODD
            // outer rect
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            // subtract ROI by adding it as inner path
            roiPath?.let { addPath(it) }
        }

        // Make sure transparentPaint is fill style and uses semi-translucent color:
        transparentPaint.style = Paint.Style.FILL
        canvas.drawPath(dimPath, transparentPaint)

        // Draw ROI border and optional capture indicator
        roiPath?.let {
            canvas.drawPath(it, roiPaint) // border
            if (showCaptureIndicator) canvas.drawPath(it, captureIndicatorPaint)
        }

        // Draw center text / countdown relative to roiBounds
        roiBounds?.let { roi ->
            val centerX = (roi.left + roi.right) / 2f
            val centerY = (roi.top + roi.bottom) / 2f
            val roiW = roi.width()
            val roiH = roi.height()
            val minDim = minOf(roiW, roiH)
            var messageSize = (minDim * 0.10f).coerceAtLeast(24f)
            messagePaint.textSize = messageSize
            if (showCenterMessage) {
                val allowed = roiW * 0.9f
                val measured = messagePaint.measureText(centerMessage)
                if (measured > 0f && measured > allowed) {
                    val scale = allowed / measured
                    messageSize = (messageSize * scale).coerceAtLeast(18f)
                    messagePaint.textSize = messageSize
                }
                canvas.drawText(centerMessage, centerX, centerY, messagePaint)
            }
            if (showCountdown) {
                canvas.drawText(countdownValue.toString(), centerX, centerY + 100f, countdownPaint)
            }
        }
    }

    fun setPreviewContentRect(rect: RectF) {
        contentBounds = rect
        // If you want overlay to shrink to that rect's vertical bounds:
        post {
            val lp = layoutParams
            lp?.let {
                it.height = rect.height().toInt()
                // If you want it placed at the right Y, also set top margin to rect.top
                if (it is ViewGroup.MarginLayoutParams) {
                    it.topMargin = rect.top.toInt()
                }
                layoutParams = it
            }
            invalidate()
        }
    }


//    @SuppressLint("DrawAllocation")
//    override fun onDraw(canvas: Canvas) {
//        super.onDraw(canvas)
//
//        // Dim whole view
//        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), transparentPaint)
//
//        // If caller provided preview content rect (in view coords), use it.
//        val content = contentBounds
//        val roiLeft: Float
//        val roiTop: Float
//        val roiWidth: Float
//        val roiHeight: Float
//
//        if (content != null) {
//            // Use contentBounds (this should be in overlay view coordinates already)
//            roiLeft = content.left
//            roiTop = content.top
//            roiWidth = content.width()
//            roiHeight = content.height()
//        } else {
//            // Fallback to full view-based ROI (what you had before)
//            val widthFactor = 0.95f
//            val heightFactor = 0.55f
//            roiWidth = width * widthFactor
//            roiHeight = height * heightFactor
//            roiLeft = (width - roiWidth) / 2f
//            roiTop = (height - roiHeight) / 2f
//        }
//
//        val left = roiLeft
//        val top = roiTop
//        val right = left + roiWidth
//        val bottom = top + roiHeight
//        val radius = roiHeight / 2f
//
//        // Save roi bounds (in overlay/view coordinates)
//        roiBounds = RectF(left, top, right, bottom)
//
//        // Build D-shape path anchored to roiBounds
//        roiPath = Path().apply {
//            moveTo(left, top)
//            lineTo(right - radius, top)
//            arcTo(RectF(right - 2 * radius, top, right, bottom), -90f, 180f)
//            lineTo(left, bottom)
//            close()
//        }
//
//        // Cut out and draw border, etc.
//        canvas.drawPath(roiPath!!, clearPaint)
//        canvas.drawPath(roiPath!!, roiPaint)
//        if (showCaptureIndicator) canvas.drawPath(roiPath!!, captureIndicatorPaint)
//
//        // Draw center text / countdown relative to roiBounds center (unchanged)
//        roiBounds?.let { roi ->
//            val centerX = (roi.left + roi.right) / 2f
//            val centerY = (roi.top + roi.bottom) / 2f
//            val roiW = roi.width()
//            val roiH = roi.height()
//            val minDim = minOf(roiW, roiH)
//            var messageSize = (minDim * 0.10f).coerceAtLeast(24f)
//            messagePaint.textSize = messageSize
//            if (showCenterMessage) {
//                val allowed = roiW * 0.9f
//                val measured = messagePaint.measureText(centerMessage)
//                if (measured > 0f && measured > allowed) {
//                    val scale = allowed / measured
//                    messageSize = (messageSize * scale).coerceAtLeast(18f)
//                    messagePaint.textSize = messageSize
//                }
//                canvas.drawText(centerMessage, centerX, centerY, messagePaint)
//            }
//            if (showCountdown) {
//                canvas.drawText(countdownValue.toString(), centerX, centerY + 100f, countdownPaint)
//            }
//        }
//    }

    fun showCaptureIndicator(show: Boolean) {
        showCaptureIndicator = show
        invalidate()
    }

    // Countdown methods
    fun showCountdown(value: Int) {
        countdownValue = value
        showCountdown = true
        invalidate()
    }

    fun hideCountdown() {
        showCountdown = false
        invalidate()
    }

    fun getRoiBounds(): RectF? = roiBounds

    fun setContentBounds(bounds: RectF?) {
        contentBounds = bounds
        invalidate()
    }

    fun showMessage(message: String) {
        centerMessage = message
        showCenterMessage = true
        invalidate()
    }

    fun hideMessage() {
        showCenterMessage = false
        invalidate()
    }
}




//    @SuppressLint("DrawAllocation")
//    override fun onDraw(canvas: Canvas) {
//        super.onDraw(canvas)
//
//        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), transparentPaint)
//
////        val widthFactor = 0.95f
////        val heightFactor = 0.55f
//           val widthFactor = 0.95f  // Close to full width, slight margin to avoid edge artifacts
//           val heightFactor = 0.95f  // Nearly full height to eliminate top/bottom space
//
//        // Uses entire view dimensions for ROI calculation, matching camera preview
//        val roiWidth = width * widthFactor
//        val roiHeight = height * heightFactor
//
//        // Center the ROI within the view
////        val left = (width - roiWidth) / 2
////        val top = (height - roiHeight) / 2
//        val left = (width - roiWidth) / 2
//        val top = (height - roiHeight) / 2
//        val right = left + roiWidth
//        val bottom = top + roiHeight
//        val radius = roiHeight / 2
//
//        Log.d("OverlayView", "Drawing D-shape: view=${width}x${height}, ROI=${roiWidth}x${roiHeight}")
//        Log.d("OverlayView", "ROI position: left=$left, top=$top, right=$right, bottom=$bottom")
//
//        // Save ROI bounds for MainActivity
//        roiBounds = RectF(left, top, right, bottom)
//
//        // Create custom path (Left straight, Right oval)
//        roiPath = Path().apply {
//            moveTo(left, top)
//            lineTo(right - radius, top)
//            arcTo(RectF(right - 2 * radius, top, right, bottom), -90f, 180f)
//            lineTo(left, bottom)
//            close()
//        }
//
//        // Clear the ROI area
//        canvas.drawPath(roiPath!!, clearPaint)
//
//        // Draw ROI border
//        canvas.drawPath(roiPath!!, roiPaint)
//
//        // Draw capture indicator if active
//        if (showCaptureIndicator) {
//            canvas.drawPath(roiPath!!, captureIndicatorPaint)
//        }
//
//        // Draw center message if available and countdown overlay if active
//        roiBounds?.let { roi ->
//            val centerX = (roi.left + roi.right) / 2f
//            val centerY = (roi.top + roi.bottom) / 2f
//
//            // Responsive text sizes based on ROI size
//            val roiW = roi.right - roi.left
//            val roiH = roi.bottom - roi.top
//            val minDim = minOf(roiW, roiH)
//
//            // Message base size ~10% of min ROI dimension
//            var messageSize = (minDim * 0.10f).coerceAtLeast(24f)
//            messagePaint.textSize = messageSize
//
//            if (showCenterMessage) {
//                // Fit message width into ~90% of ROI width
//                val allowed = roiW * 0.9f
//                val measured = messagePaint.measureText(centerMessage)
//                if (measured > 0f && measured > allowed) {
//                    val scale = allowed / measured
//                    messageSize = (messageSize * scale).coerceAtLeast(18f)
//                    messagePaint.textSize = messageSize
//                }
//                canvas.drawText(centerMessage, centerX, centerY, messagePaint)
//            }
//
//            // Draw countdown if active
//            if (showCountdown) {
//                val countdownText = countdownValue.toString()
//                canvas.drawText(countdownText, centerX, centerY + 100f, countdownPaint)
//            }
//        }
//    }
