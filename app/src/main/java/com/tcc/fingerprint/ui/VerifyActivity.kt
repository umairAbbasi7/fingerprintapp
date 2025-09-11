package com.tcc.fingerprint.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R

class VerifyActivity : AppCompatActivity() {
    
    private lateinit var userIdEdit: EditText
    private lateinit var verifyButton: Button
    private lateinit var backButton: Button
    
    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify)
        
        initializeViews()
        setupListeners()
        
        // Check if we have a user ID from "Verify Again" button
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        if (!userId.isNullOrEmpty()) {
            userIdEdit.setText(userId)
        }
    }
    
    private fun initializeViews() {
        userIdEdit = findViewById(R.id.userIdEdit)
        verifyButton = findViewById(R.id.verifyButton)
        backButton = findViewById(R.id.backButton)
    }
    
    private fun setupListeners() {
        verifyButton.setOnClickListener {
            if (validateInput()) {
                startCaptureActivity()
            }
        }
        
        backButton.setOnClickListener {
            finish()
        }
    }
    
    private fun validateInput(): Boolean {
        val userId = userIdEdit.text.toString().trim()
        
        if (userId.isEmpty()) {
            Toast.makeText(this, "Please enter a User ID", Toast.LENGTH_SHORT).show()
            return false
        }
        
        return true
    }
    
    private fun startCaptureActivity() {
        val userId = userIdEdit.text.toString().trim()
        
        val intent = Intent(this, CaptureFingerActivity::class.java).apply {
            putExtra(CaptureFingerActivity.EXTRA_MODE, "verify")
            putExtra(CaptureFingerActivity.EXTRA_USER_ID, userId)
        }
        
        // Start capture; result UI is handled by ResultActivity
        startActivity(intent)
    }
    
} 