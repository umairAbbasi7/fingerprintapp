package com.tcc.fingerprint.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R
import com.tcc.fingerprint.data.SharedPreferencesManager
import com.tcc.fingerprint.network.ApiRepository
import com.tcc.fingerprint.network.ApiResponse
import com.tcc.fingerprint.network.RetrofitClient
import java.io.File
import android.graphics.Matrix
import android.os.Environment

class PreviewActivity : AppCompatActivity() {
    
    private lateinit var imageView: ImageView
    private lateinit var uploadButton: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressOverlay: View
    private lateinit var progressIcon: ImageView
    private lateinit var progressLabel: TextView
    
    private lateinit var prefsManager: SharedPreferencesManager
    private lateinit var apiRepository: ApiRepository
    
    private var capturedBitmap: Bitmap? = null
    private var currentImagePath: String? = null
    private var userId: String = ""
    private var captureMode: String = "register"
    
    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_MODE = "extra_mode"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        
        initializeViews()
        initializeComponents()
        loadImage()
        setupListeners()
    }
    
    private fun initializeViews() {
        imageView = findViewById(R.id.imageView)
        uploadButton = findViewById(R.id.uploadButton)
        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)
        statusText = findViewById(R.id.statusTextCameraCapture)
        progressOverlay = findViewById(R.id.progressOverlay)
        progressIcon = findViewById(R.id.progressIcon)
        progressLabel = findViewById(R.id.progressLabel)
    }
    
    private fun initializeComponents() {
        prefsManager = SharedPreferencesManager(this)
        val retrofitClient = RetrofitClient(prefsManager)
        apiRepository = ApiRepository(this, prefsManager, retrofitClient)
        
        // Set up status updates
        apiRepository.onStatusUpdate = { status ->
            runOnUiThread {
                statusText.text = status
            }
        }
    }
    
    private fun loadImage() {
        // Get the image path from intent
        val imagePath = intent.getStringExtra("imagePath") ?: intent.getStringExtra(EXTRA_IMAGE_PATH)
        userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        captureMode = intent.getStringExtra(EXTRA_MODE) ?: "register"
        
        Log.d("PreviewActivity", "Loading image")
        
        if (userId.isBlank()) {
            statusText.text = "Error: Missing User ID"
            uploadButton.isEnabled = false
            saveButton.isEnabled = false
            Toast.makeText(this, "Missing User ID", Toast.LENGTH_SHORT).show()
            return
        }

        if (!imagePath.isNullOrBlank()) {
            val file = File(imagePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                if (bitmap != null) {
                    // Rotates landscape images to correct display orientation for portrait presentation
                    val displayBitmap = fixDisplayOrientation(bitmap)
                    
                    capturedBitmap = displayBitmap
                    currentImagePath = imagePath
                    imageView.setImageBitmap(displayBitmap)
                    statusText.text = "Image loaded successfully"
                    
                    Log.d("PreviewActivity", "Image loaded successfully")
                } else {
                    statusText.text = "Error loading image"
                    uploadButton.isEnabled = false
                    saveButton.isEnabled = false
                    Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
                    Log.e("PreviewActivity", "Failed to decode image")
                }
            } else {
                statusText.text = "Error: Image file not found"
                uploadButton.isEnabled = false
                saveButton.isEnabled = false
                Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
                Log.e("PreviewActivity", "Image file does not exist")
            }
        } else {
            statusText.text = "Error: No image path received"
            uploadButton.isEnabled = false
            saveButton.isEnabled = false
            Toast.makeText(this, "No image path received", Toast.LENGTH_SHORT).show()
            Log.e("PreviewActivity", "No image path in intent")
        }
        
        // For verify mode, hide save button and change upload button text
        if (captureMode == "verify") {
            saveButton.visibility = android.view.View.GONE
            uploadButton.text = "Verify Fingerprint"
        }
    }
    
    private fun setupListeners() {
        uploadButton.setOnClickListener { navigateToUpload() }
        
        saveButton.setOnClickListener {
            saveToGallery()
        }
        
        cancelButton.setOnClickListener {
            // Navigate back to home page (MainActivity)
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
    
    private fun navigateToUpload() {
        val imagePath = currentImagePath
            ?: intent.getStringExtra("imagePath")
            ?: intent.getStringExtra(EXTRA_IMAGE_PATH)
        val originalPath = intent.getStringExtra("imagePathOriginal")
        
        if (imagePath.isNullOrBlank() || !File(imagePath).exists()) {
            Toast.makeText(this, "No image to upload", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, UploadActivity::class.java)
        intent.putExtra(EXTRA_IMAGE_PATH, imagePath)
        if (!originalPath.isNullOrBlank()) intent.putExtra("imagePathOriginal", originalPath)
        intent.putExtra(EXTRA_USER_ID, userId)
        intent.putExtra(EXTRA_MODE, captureMode)
        startActivity(intent)
    }
    
    // Upload logic moved to UploadActivity
    
    private fun handleUploadSuccess(response: ApiResponse) {
        uploadButton.isEnabled = true
        saveButton.isEnabled = true
        hideProgress()
        // Navigate to success UI page
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra(ResultActivity.EXTRA_SUCCESS, true)
        intent.putExtra(ResultActivity.EXTRA_MESSAGE, response.message)
        intent.putExtra(ResultActivity.EXTRA_MODE, captureMode)
        intent.putExtra(ResultActivity.EXTRA_USER_ID, userId)
        startActivity(intent)
        finish()
    }
    
    private fun handleUploadError(error: String) {
        uploadButton.isEnabled = true
        saveButton.isEnabled = true
        hideProgress()
        // Route 422 validation to dedicated page, others too
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra(ResultActivity.EXTRA_SUCCESS, false)
        intent.putExtra(ResultActivity.EXTRA_MESSAGE, error)
        intent.putExtra(ResultActivity.EXTRA_MODE, captureMode)
        intent.putExtra(ResultActivity.EXTRA_USER_ID, userId)
        startActivity(intent)
        finish()
    }
    
    private fun saveToGallery() {
        val bitmap = capturedBitmap
        if (bitmap != null) {
            // Save only the cropped D-overlay image (cleaner approach)
            Log.d("PreviewActivity", "Saving cropped D-overlay image to gallery")
            
            val success = saveBitmapToGallery(bitmap, "fingerprint_cropped")
            if (success) {
                Toast.makeText(this, "Cropped fingerprint image saved to gallery", Toast.LENGTH_LONG).show()
                Log.d("PreviewActivity", "Image saved to gallery successfully")
            } else {
                Toast.makeText(this, "Failed to save image to gallery", Toast.LENGTH_SHORT).show()
                Log.e("PreviewActivity", "Failed to save image to gallery")
            }
        } else {
            Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
            Log.w("PreviewActivity", "No image available for saving")
        }
    }
    
    private fun saveBitmapToGallery(bitmap: Bitmap, prefix: String = "fingerprint"): Boolean {
        return try {
            Log.d("PreviewActivity", "Saving bitmap to gallery")
            
            val fileName = "${prefix}_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { imageUri ->
                contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                            Log.d("PreviewActivity", "Image saved to gallery: $fileName")
            return true
        }
        
        Log.e("PreviewActivity", "Failed to create content URI")
        return false
            
        } catch (e: Exception) {
            Log.e("PreviewActivity", "Error saving to gallery: ${e.message}")
            return false
        }
    }
    
    private fun handleApiError(error: String) {
        Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        statusText.text = "Error: $error"
        uploadButton.isEnabled = true
        saveButton.isEnabled = true
    }

    private fun showProgress(message: String) {
        progressLabel.text = message
        progressOverlay.visibility = View.VISIBLE
        // simple pulse animation for the capsule/icon
        val anim = ScaleAnimation(
            0.9f, 1.1f, 0.9f, 1.1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        )
        anim.duration = 700
        anim.repeatMode = ScaleAnimation.REVERSE
        anim.repeatCount = ScaleAnimation.INFINITE
        anim.interpolator = AccelerateDecelerateInterpolator()
        progressIcon.startAnimation(anim)
    }

    private fun hideProgress() {
        progressOverlay.visibility = View.GONE
        progressIcon.clearAnimation()
    }
    
    /**
     * Fix display orientation for landscape images
     * Rotates landscape images by 90 degrees for proper portrait display
     */
    private fun fixDisplayOrientation(bitmap: Bitmap): Bitmap {
        return try {
                    if (bitmap.width > bitmap.height) { // If landscape
            Log.d("PreviewActivity", "Rotating landscape image to portrait for display")
            val matrix = Matrix()
            matrix.postRotate(90f) // Rotate 90 degrees clockwise
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            Log.d("PreviewActivity", "Display orientation fixed")
            return rotatedBitmap
        } else {
            Log.d("PreviewActivity", "No rotation needed - image is already portrait/square")
            return bitmap
        }
        } catch (e: Exception) {
            Log.e("PreviewActivity", "Error fixing display orientation: ${e.message}")
            return bitmap
        }
    }
} 