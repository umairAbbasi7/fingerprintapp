package com.tcc.fingerprint.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tcc.fingerprint.R
import com.tcc.fingerprint.data.SharedPreferencesManager
import com.tcc.fingerprint.network.ApiRepository
import com.tcc.fingerprint.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UploadActivity : AppCompatActivity() {

    private lateinit var progressOverlay: View
    private lateinit var progressIcon: ImageView
    private lateinit var progressLabel: TextView
    private lateinit var statusText: TextView

    private lateinit var apiRepository: ApiRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload)

        progressOverlay = findViewById(R.id.progressOverlay)
        progressIcon = findViewById(R.id.progressIcon)
        progressLabel = findViewById(R.id.progressLabel)
        statusText = findViewById(R.id.statusTextCameraCapture)

        apiRepository = ApiRepository(this, SharedPreferencesManager(this), RetrofitClient(SharedPreferencesManager(this)))
        apiRepository.onStatusUpdate = { msg -> runOnUiThread { statusText.text = msg } }

        // Kick off upload immediately
        startUpload()
    }

    private fun startUpload() {
        val imagePath = intent.getStringExtra(PreviewActivity.EXTRA_IMAGE_PATH)
        val originalPath = intent.getStringExtra("imagePathOriginal")
        val userId = intent.getStringExtra(PreviewActivity.EXTRA_USER_ID) ?: ""
        val mode = intent.getStringExtra(PreviewActivity.EXTRA_MODE) ?: "register"

        if (imagePath.isNullOrBlank() || !File(imagePath).exists()) {
            Toast.makeText(this, "No image to upload", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (userId.isBlank()) {
            Toast.makeText(this, "Missing User ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showProgress(if (mode == "verify") "Verifying..." else "Uploading...")

        val bitmap = BitmapFactory.decodeFile(imagePath)
        val origBitmap = originalPath?.let { path -> if (File(path).exists()) BitmapFactory.decodeFile(path) else null }
        if (bitmap == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Fix orientation for server upload (same as gallery save)
        val correctedBitmap = fixDisplayOrientation(bitmap)
        Log.d("UploadActivity", "Orientation corrected for server upload")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = if (mode == "register") {
                    apiRepository.registerFingerprint(userId, correctedBitmap)
                } else {
                    // Optionally upload both; server can choose which to use
                    apiRepository.verifyFingerprint(userId, correctedBitmap)
                }

                result.fold(
                    onSuccess = { response ->
                        withContext(Dispatchers.Main) {
                            hideProgress()
                            val intent = Intent(this@UploadActivity, ResultActivity::class.java)
                            intent.putExtra(ResultActivity.EXTRA_SUCCESS, response.success)
                            intent.putExtra(ResultActivity.EXTRA_MESSAGE, response.message)
                            intent.putExtra(ResultActivity.EXTRA_MODE, mode)
                            intent.putExtra(ResultActivity.EXTRA_USER_ID, userId)
                            startActivity(intent)
                            finish()
                        }
                    },
                    onFailure = { exception ->
                        withContext(Dispatchers.Main) {
                            hideProgress()
                            val intent = Intent(this@UploadActivity, ResultActivity::class.java)
                            intent.putExtra(ResultActivity.EXTRA_SUCCESS, false)
                            intent.putExtra(ResultActivity.EXTRA_MESSAGE, exception.message ?: "Upload failed")
                            intent.putExtra(ResultActivity.EXTRA_MODE, mode)
                            intent.putExtra(ResultActivity.EXTRA_USER_ID, userId)
                            startActivity(intent)
                            finish()
                        }
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    val intent = Intent(this@UploadActivity, ResultActivity::class.java)
                    intent.putExtra(ResultActivity.EXTRA_SUCCESS, false)
                    intent.putExtra(ResultActivity.EXTRA_MESSAGE, e.message ?: "Upload failed")
                    intent.putExtra(ResultActivity.EXTRA_MODE, mode)
                    intent.putExtra(ResultActivity.EXTRA_USER_ID, userId)
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun showProgress(message: String) {
        progressLabel.text = message
        progressOverlay.visibility = View.VISIBLE
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
     * Fix display orientation for server upload (same as gallery save)
     * Rotates landscape images by 90 degrees for proper server processing
     * Ensures consistency between gallery save and server upload
     */
    private fun fixDisplayOrientation(bitmap: Bitmap): Bitmap {
        return try {
                    if (bitmap.width > bitmap.height) { // If landscape
            Log.d("UploadActivity", "Rotating landscape image to portrait for server upload")
            val matrix = Matrix()
            matrix.postRotate(90f) // Rotate 90 degrees clockwise
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            Log.d("UploadActivity", "Server upload orientation fixed")
            return rotatedBitmap
        } else {
            Log.d("UploadActivity", "No rotation needed for server upload - image is already portrait/square")
            return bitmap
        }
        } catch (e: Exception) {
            Log.e("UploadActivity", "Error fixing server upload orientation: ${e.message}")
            return bitmap
        }
    }
}

