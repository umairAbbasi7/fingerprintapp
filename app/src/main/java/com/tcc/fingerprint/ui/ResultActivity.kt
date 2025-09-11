package com.tcc.fingerprint.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R

class ResultActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_USER_ID = "extra_user_id"
    }
    
    private lateinit var resultIcon: ImageView
    private lateinit var resultTitle: TextView
    private lateinit var resultMessage: TextView
    private lateinit var detailsText: TextView
    private lateinit var homeButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        
        resultIcon = findViewById(R.id.resultIcon)
        resultTitle = findViewById(R.id.resultTitle)
        resultMessage = findViewById(R.id.resultMessage)
        detailsText = findViewById(R.id.detailsText)
        homeButton = findViewById(R.id.homeButton)
        
        val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "No message"
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "register"
        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        
        setupResultUI(success, message, mode, userId)
        setupButtons()
        startResultAnimation(success)
    }
    
    private fun setupResultUI(success: Boolean, message: String, mode: String, userId: String) {
        // Set dynamic title based on mode and success
        val title = when {
            success && mode == "verify" -> "Verification Successful"
            success && mode == "register" -> "Registration Successful"
            !success && mode == "verify" -> "Verification Failed"
            !success && mode == "register" -> "Registration Failed"
            else -> if (success) "Operation Successful" else "Operation Failed"
        }
        
        // Set different icons and colors based on mode and success
        if (success) {
            if (mode == "register") {
                resultIcon.setImageResource(R.drawable.ic_fingerprint_success)
            } else {
                resultIcon.setImageResource(R.drawable.ic_verification_success)
            }
            resultTitle.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
        } else {
            resultIcon.setImageResource(R.drawable.ic_error)
            resultTitle.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
        }
        
        // Set title and message
        resultTitle.text = title
        resultMessage.text = message
        
        // Set dynamic details with mode-specific information
        val summary = if (success) {
            if (mode == "register") {
                buildString {
                    appendLine("Status: 200 OK")
                    appendLine("User: ${userId}")
                    appendLine("Fingerprint stored successfully")
                }
            } else {
                buildString {
                    appendLine("Status: 200 OK")
                    appendLine("User: ${userId}")
                    appendLine("Identity verified successfully")
                }
            }
        } else {
            buildString {
                appendLine("Status: Error")
                appendLine("User: ${userId}")
                if (message.isNotBlank()) {
                    appendLine("Message: ${message}")
                }
            }
        }
        detailsText.text = summary.trim()
        detailsText.visibility = View.VISIBLE
    }
    
    private fun setupButtons() {
        homeButton.setOnClickListener {
            // Go to main activity
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
    
    private fun startResultAnimation(success: Boolean) {
        // Start icon animation
        val scaleAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        resultIcon.startAnimation(scaleAnimation)
        
        // Start title animation
        val slideIn = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
        resultTitle.startAnimation(slideIn)
        
        // Start message animation with delay
        resultMessage.postDelayed({
            val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            resultMessage.startAnimation(fadeIn)
        }, 300)
        
        // Only Home button should be displayed
        homeButton.postDelayed({
            homeButton.visibility = View.VISIBLE
            val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            homeButton.startAnimation(fadeIn)
        }, 800)
    }
} 


