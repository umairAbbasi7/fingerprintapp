package com.tcc.fingerprint.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R

class RegisterActivity : AppCompatActivity() {
    
    private lateinit var userIdEdit: EditText
    private lateinit var registerButton: Button
    private lateinit var backButton: Button
    
    companion object {
        const val REGISTER_REQUEST = 1001
                const val EXTRA_MODE = "capture_mode"
        const val EXTRA_USER_ID = "user_id"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        
        initializeViews()
        setupListeners()
    }
    
    private fun initializeViews() {
        userIdEdit = findViewById(R.id.userIdEdit)
        registerButton = findViewById(R.id.registerButton)
        backButton = findViewById(R.id.backButton)
    }
    
    private fun setupListeners() {
        registerButton.setOnClickListener {
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
        
        if (userId.length < 3) {
            Toast.makeText(this, "User ID must be at least 3 characters", Toast.LENGTH_SHORT).show()
            return false
        }
        
        return true
    }
    
    private fun startCaptureActivity() {
        val userId = userIdEdit.text.toString().trim()
        
        val intent = Intent(this, CaptureFingerActivity::class.java).apply {
            putExtra(CaptureFingerActivity.EXTRA_MODE, "register")
            putExtra(CaptureFingerActivity.EXTRA_USER_ID, userId)
        }
        
        startActivityForResult(intent, REGISTER_REQUEST)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REGISTER_REQUEST) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Registration completed successfully", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "Registration failed", Toast.LENGTH_LONG).show()
            }
        }
    }
} 