package com.tcc.fingerprint.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupButtons()
        setupVersionDisplay()
    }
    
    private fun setupButtons() {
        findViewById<View>(R.id.btnRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        
        findViewById<View>(R.id.btnVerify).setOnClickListener {
            startActivity(Intent(this, VerifyActivity::class.java))
        }
        
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    /**
     * Displays the current application version and build number at the bottom of the screen.
     * This implementation uses the recommended API to ensure forward compatibility and maintainability.
     */
    private fun setupVersionDisplay() {
        try {
            // Use newer API to avoid deprecation warning
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            
            val versionName = packageInfo.versionName
            val versionCode = packageInfo.longVersionCode
            
            // Update version display
            findViewById<TextView>(R.id.tvVersion).text = "Version $versionName"
            findViewById<TextView>(R.id.tvBuildInfo).text = "Build $versionCode"
            
        } catch (e: Exception) {
            // Fallback to default values if package info not available
            findViewById<TextView>(R.id.tvVersion).text = "Version 1.1.1"
            findViewById<TextView>(R.id.tvBuildInfo).text = "Build 111"
        }
    }
} 