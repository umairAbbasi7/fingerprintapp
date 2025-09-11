package com.tcc.fingerprint.data

import android.content.Context
import android.content.SharedPreferences
import com.tcc.fingerprint.utils.Constants

class SharedPreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "fingerprint_prefs",
        Context.MODE_PRIVATE
    )

    // API Settings
    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", Constants.BASE_URL) ?: Constants.BASE_URL
        set(value) = prefs.edit().putString("api_base_url", value).apply()

    var apiTimeout: Long
        get() = prefs.getLong("api_timeout", Constants.API_TIMEOUT)
        set(value) = prefs.edit().putLong("api_timeout", value).apply()

    var maxRetries: Int
        get() = prefs.getInt("max_retries", Constants.MAX_RETRIES)
        set(value) = prefs.edit().putInt("max_retries", value).apply()

    // Detection Settings
    var confidenceThreshold: Float
        get() = prefs.getFloat("confidence_threshold", Constants.CONFIDENCE_THRESHOLD)
        set(value) = prefs.edit().putFloat("confidence_threshold", value).apply()

    var stabilityThreshold: Float
        get() = prefs.getFloat("stability_threshold", Constants.STABILITY_THRESHOLD)
        set(value) = prefs.edit().putFloat("stability_threshold", value).apply()

    var qualityThreshold: Float
        get() = prefs.getFloat("quality_threshold", Constants.QUALITY_THRESHOLD)
        set(value) = prefs.edit().putFloat("quality_threshold", value).apply()

    // Camera Settings
    var useTorch: Boolean
        get() = prefs.getBoolean("use_torch", false)
        set(value) = prefs.edit().putBoolean("use_torch", value).apply()

    var autoFocus: Boolean
        get() = prefs.getBoolean("auto_focus", true)
        set(value) = prefs.edit().putBoolean("auto_focus", value).apply()

    var exposureCompensation: Int
        get() = prefs.getInt("exposure_compensation", Constants.NATURAL_LIGHT_EXPOSURE)
        set(value) = prefs.edit().putInt("exposure_compensation", value).apply()

    // Processing Settings
    var enableCvMetrics: Boolean
        get() = prefs.getBoolean("enable_cv_metrics", true)
        set(value) = prefs.edit().putBoolean("enable_cv_metrics", value).apply()

    var enableCvEnhancement: Boolean
        get() = prefs.getBoolean("enable_cv_enhancement", false)
        set(value) = prefs.edit().putBoolean("enable_cv_enhancement", value).apply()

    var claheClipLimit: Double
        get() = prefs.getFloat("clahe_clip_limit", Constants.CLAHE_CLIP_LIMIT.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("clahe_clip_limit", value.toFloat()).apply()

    var blurThreshold: Double
        get() = prefs.getFloat("blur_threshold", Constants.BLUR_THRESHOLD.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("blur_threshold", value.toFloat()).apply()

    // User Settings
    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit().putString("user_id", value).apply()

    var lastCaptureTime: Long
        get() = prefs.getLong("last_capture_time", 0L)
        set(value) = prefs.edit().putLong("last_capture_time", value).apply()

    var captureCount: Int
        get() = prefs.getInt("capture_count", 0)
        set(value) = prefs.edit().putInt("capture_count", value).apply()

    // Debug Settings
    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", false)
        set(value) = prefs.edit().putBoolean("debug_mode", value).apply()

    var saveDebugImages: Boolean
        get() = prefs.getBoolean("save_debug_images", false)
        set(value) = prefs.edit().putBoolean("save_debug_images", value).apply()

    // Clear all preferences
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // Reset to defaults
    fun resetToDefaults() {
        clearAll()
        // Re-apply default values
        apiBaseUrl = Constants.BASE_URL
        apiTimeout = Constants.API_TIMEOUT
        maxRetries = Constants.MAX_RETRIES
        confidenceThreshold = Constants.CONFIDENCE_THRESHOLD
        stabilityThreshold = Constants.STABILITY_THRESHOLD
        qualityThreshold = Constants.QUALITY_THRESHOLD
        autoFocus = true
        exposureCompensation = Constants.NATURAL_LIGHT_EXPOSURE
        claheClipLimit = Constants.CLAHE_CLIP_LIMIT
        blurThreshold = Constants.BLUR_THRESHOLD
    }
} 