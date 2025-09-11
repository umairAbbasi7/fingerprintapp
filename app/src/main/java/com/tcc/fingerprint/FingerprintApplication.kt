package com.tcc.fingerprint

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader
import com.tcc.fingerprint.data.SharedPreferencesManager

class FingerprintApplication : Application() {
    
    companion object {
        private const val TAG = "FingerprintApplication"
        var isOpenCVInitialized = false
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Initializing Fingerprint Application")
        
        // Initialize OpenCV
        initializeOpenCV()

        // Enable OpenCV enhancement by default
        try {
            val prefs = SharedPreferencesManager(this)
            prefs.enableCvMetrics = true
            prefs.enableCvEnhancement = true
            Log.d(TAG, "OpenCV enhancement enabled via preferences")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set enhancement flag: ${e.message}")
        }
    }
    
    private fun initializeOpenCV() {
        try {
            Log.d(TAG, "Initializing OpenCV...")
            
            // Try to initialize OpenCV
            if (OpenCVLoader.initDebug()) {
                Log.d(TAG, "OpenCV initialized successfully using initDebug()")
                isOpenCVInitialized = true
            } else {
                Log.w(TAG, "OpenCV initDebug() failed, trying initLocal()")
                
                if (OpenCVLoader.initLocal()) {
                    Log.d(TAG, "OpenCV initialized successfully using initLocal()")
                    isOpenCVInitialized = true
                } else {
                    Log.e(TAG, "OpenCV initialization failed with both methods")
                    isOpenCVInitialized = false
                }
            }
            
            if (isOpenCVInitialized) {
                Log.d(TAG, "OpenCV is ready for use")
            } else {
                Log.e(TAG, "OpenCV initialization failed - app may crash when using OpenCV features")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OpenCV: ${e.message}")
            isOpenCVInitialized = false
        }
    }
} 