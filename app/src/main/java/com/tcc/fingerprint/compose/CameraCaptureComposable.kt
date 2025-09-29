package com.tcc.fingerprint.compose

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CaptureComposeScreen(
    onPreviewReady: (PreviewView, Int, Int) -> Unit,
    onCaptureRequested: () -> Unit,
    onTorchToggle: (Boolean) -> Unit,
    updateOverlayRect: (RectF?) -> Unit,
    qualityText: String,
    showCaptureIndicatorAutoCapture: Boolean = false,
    isCapturing: Boolean = false,
    isCountdownActive: Boolean = false,
    countdownValue: Int = 0,
    captureStatus: String = "Position 4 fingers inside the D-shape area",
    progressStep: Int = 0
) {
    val context = LocalContext.current
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var previewSize by remember { mutableStateOf(IntSize(0, 0)) }
    var torchOn by remember { mutableStateOf(true) }
    
    // Sync torch state with actual camera state
    LaunchedEffect(Unit) {
        // Wait a bit for camera to initialize, then enable torch
        kotlinx.coroutines.delay(1000)
        onTorchToggle(true)
    }
    var showCaptureIndicator by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(
        showCaptureIndicatorAutoCapture
    ) {
        showCaptureIndicator = showCaptureIndicatorAutoCapture
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewViewRef = this
                post {
                    val w = width
                    val h = height
                    previewSize = IntSize(w, h)
                    onPreviewReady(this, w, h)
                }
            }
        }, modifier = Modifier.fillMaxSize())

        if (previewSize.width > 0 && previewSize.height > 0) {
            val widthF = previewSize.width.toFloat()
            val heightF = previewSize.height.toFloat()

            val roiW = widthF * 0.85f  // Keep width for center sweet spot
            val roiH = heightF * 0.58f  // Increased vertical height for better finger coverage
            val left = (widthF - roiW) / 2f
            val top = (heightF - roiH) / 2f + heightF * 0.08f  // Move D-shape lower on screen
            val right = left + roiW
            val bottom = top + roiH
            val radius = roiH / 2f

            LaunchedEffect(left, top, right, bottom) {
                updateOverlayRect(RectF(left, top, right, bottom))
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val outer = Path().apply {
                        reset()
                        addRect(0f, 0f, widthF, heightF, Path.Direction.CW)
                    }
                    val roiPath = Path().apply {
                        reset()
                        moveTo(left, top)
                        lineTo(right - radius, top)
                        arcTo(
                            android.graphics.RectF(right - 2 * radius, top, right, bottom),
                            -90f,
                            180f,
                            false
                        )
                        lineTo(left, bottom)
                        close()
                    }
                    val outerPath = Path().apply {
                        reset()
                        addRect(0f, 0f, widthF, heightF, Path.Direction.CW)
                        addPath(roiPath)
                        fillType = Path.FillType.EVEN_ODD
                    }

                    val dimPaint = Paint().apply {
                        color = "#A6000000".toColorInt()
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    native.drawPath(outerPath, dimPaint)

                    val borderPaint = Paint().apply {
                        color = android.graphics.Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 5f
                        isAntiAlias = true
                    }
                    native.drawPath(roiPath, borderPaint)
                    
                    // Add center guidance lines to keep fingers in sweet spot
                    val guidePaint = Paint().apply {
                        color = android.graphics.Color.YELLOW
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                        isAntiAlias = true
                        alpha = 150  // Semi-transparent
                    }
                    
                    // Draw center guidelines
                    val centerX = (left + right) / 2f
                    val centerY = (top + bottom) / 2f
                    val guideSize = roiW * 0.15f
                    
                    // Horizontal center line
                    native.drawLine(centerX - guideSize, centerY, centerX + guideSize, centerY, guidePaint)
                    // Vertical center line  
                    native.drawLine(centerX, centerY - guideSize, centerX, centerY + guideSize, guidePaint)

                    if (showCaptureIndicator) {
                        val indicatorPaint = Paint().apply {
                            color = android.graphics.Color.GREEN
                            style = Paint.Style.STROKE
                            strokeWidth = 12f
                            isAntiAlias = true
                        }
                        native.drawPath(roiPath, indicatorPaint)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            )
            {
            // Countdown display
            if (isCountdownActive && countdownValue > 0) {
                Text(
                    text = countdownValue.toString(),
                    modifier = Modifier.padding(8.dp),
                    color = Color.Yellow,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            } else {
                // Progress indicator
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Main status text
                    Text(
                        text = captureStatus,
                        color = when (progressStep) {
                            0 -> Color.Red      // Positioning
                            1 -> Color.Yellow   // Detecting
                            2 -> Color.Green    // Capturing
                            3 -> Color.Blue     // Processing
                            else -> Color.Gray
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    
                    // Progress steps
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Step 1: Positioning
                        Text(
                            text = "1️⃣",
                            fontSize = 16.sp,
                            color = if (progressStep >= 0) Color.Green else Color.Gray
                        )
                        Text(
                            text = "Position",
                            fontSize = 12.sp,
                            color = if (progressStep >= 0) Color.Green else Color.Gray
                        )
                        
                        // Step 2: Detecting
                        Text(
                            text = "2️⃣",
                            fontSize = 16.sp,
                            color = if (progressStep >= 1) Color.Green else Color.Gray
                        )
                        Text(
                            text = "Detect",
                            fontSize = 12.sp,
                            color = if (progressStep >= 1) Color.Green else Color.Gray
                        )
                        
                        // Step 3: Capturing
                        Text(
                            text = "3️⃣",
                            fontSize = 16.sp,
                            color = if (progressStep >= 2) Color.Green else Color.Gray
                        )
                        Text(
                            text = "Capture",
                            fontSize = 12.sp,
                            color = if (progressStep >= 2) Color.Green else Color.Gray
                        )
                        
                        // Step 4: Complete
                        Text(
                            text = "4️⃣",
                            fontSize = 16.sp,
                            color = if (progressStep >= 3) Color.Green else Color.Gray
                        )
                        Text(
                            text = "Complete",
                            fontSize = 12.sp,
                            color = if (progressStep >= 3) Color.Green else Color.Gray
                        )
                    }
                }
            }
                IconButton(onClick = {
                    torchOn = !torchOn
                    onTorchToggle(torchOn)
                },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.LightGray.copy(alpha = .4f))
                        .padding(4.dp)
                ) {
                    if (torchOn) Icon(
                        Icons.Default.FlashOn,
                        contentDescription = "Torch on",
                        tint = Color.White
                    )
                    else Icon(
                        Icons.Default.FlashOff,
                        contentDescription = "Torch off",
                        tint = Color.White
                    )
                }
            }
            Text(text = qualityText, modifier = Modifier.padding(8.dp), color = Color.White)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                showCaptureIndicator = true
                onCaptureRequested()
                scope.launch {
                    delay(700)
                    showCaptureIndicator = false
                }
            }) {
                Text("Capture")
            }
        }
    }
}
