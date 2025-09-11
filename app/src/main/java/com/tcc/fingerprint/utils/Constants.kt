package com.tcc.fingerprint.utils

object Constants {
    // API Configuration
    const val BASE_URL = "https://api.fingerprint.com/"
    const val API_TIMEOUT = 30L  // Default timeout (30 seconds)
    const val MAX_RETRIES = 3    // Default retries (3 attempts)
    
    // Camera Configuration
    const val PREVIEW_WIDTH = 640
    const val PREVIEW_HEIGHT = 480
    const val CAPTURE_WIDTH = 1920
    const val CAPTURE_HEIGHT = 1080
    const val FPS = 30
    
    // YOLO Detection
    const val YOLO_INPUT_SIZE = 640
    const val CONFIDENCE_THRESHOLD = 0.5f
    const val NMS_THRESHOLD = 0.4f
    const val MAX_DETECTIONS = 10
    
    // Finger Tracking
    const val STABILITY_THRESHOLD = 0.8f
    const val TRACKING_HISTORY_SIZE = 10
    const val MIN_STABLE_FRAMES = 5
    const val CAPTURE_DELAY_MS = 800L
    
    // Image Processing
    const val CLAHE_CLIP_LIMIT = 2.0
    const val CLAHE_TILE_GRID_SIZE = 8
    const val BLUR_THRESHOLD = 100.0
    const val CONTRAST_THRESHOLD = 30.0
 const val GLARE_THRESHOLD = 0.05
    const val QUALITY_THRESHOLD = 0.7f
    
    // Exposure Settings
    const val TORCH_EXPOSURE = 0
    const val NATURAL_LIGHT_EXPOSURE = -1
    
    // Retry Configuration
    const val AE_RETRY_DELAY_MS = 200L
    const val AE_TIMEOUT_MS = 4000L
    const val CAPTURE_RETRY_DELAY_MS = 800L
    
    // Histogram Analysis
    const val HISTOGRAM_MIN_RANGE = 220
    const val HISTOGRAM_MAX_RANGE = 255
    const val HISTOGRAM_THRESHOLD = 0.8f
    
    // File Configuration
    const val IMAGE_QUALITY = 95
    const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB
    
    // UI Configuration
    const val ANIMATION_DURATION = 300L
    const val STATUS_UPDATE_DELAY = 100L
    
    // Error Messages
    const val ERROR_CAMERA_PERMISSION = "Camera permission is required"
    const val ERROR_CAMERA_INIT = "Failed to initialize camera"
    const val ERROR_DETECTION_FAILED = "Finger detection failed"
    const val ERROR_NETWORK = "Network error occurred"
    const val ERROR_API_FAILED = "API request failed"
    const val ERROR_IMAGE_PROCESSING = "Image processing failed"
    
    // Success Messages
    const val SUCCESS_CAPTURE = "Fingerprint captured successfully"
    const val SUCCESS_REGISTRATION = "Registration completed"
    const val SUCCESS_VERIFICATION = "Verification completed"
    
    // Status Messages
    const val STATUS_DETECTING = "Detecting finger..."
    const val STATUS_POSITIONING = "Positioning finger..."
    const val STATUS_CAPTURING = "Capturing fingerprint..."
    const val STATUS_PROCESSING = "Processing image..."
    const val STATUS_UPLOADING = "Uploading to server..."
    const val STATUS_WAITING_RESPONSE = "Waiting for server response..."
    const val STATUS_RETRYING = "Retrying upload (attempt %d/%d)..."
    const val STATUS_PROCESSING_RESPONSE = "Processing server response..."
    const val STATUS_READY = "Ready to capture"
} 