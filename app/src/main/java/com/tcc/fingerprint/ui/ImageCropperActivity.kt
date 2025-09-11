package com.tcc.fingerprint.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.canhub.cropper.CropImageView
import com.tcc.fingerprint.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.createBitmap

class ImageCropperActivity : AppCompatActivity() {

    private lateinit var cropImageView: CropImageView

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_image_cropper)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cropImageView = findViewById(R.id.cropImageView)

        // Read path from intent (expected to be a file path)
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val userId = intent.getStringExtra(PreviewActivity.EXTRA_USER_ID)
        val mode = intent.getStringExtra(PreviewActivity.EXTRA_MODE)
        if (imagePath.isNullOrBlank()) {
            Toast.makeText(this, "No image provided to crop", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val sourceFile = File(imagePath)
        if (!sourceFile.exists()) {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val sourceUri = Uri.fromFile(sourceFile)
        // Load image into CropImageView asynchronously (safe on UI thread)
        cropImageView.setImageUriAsync(sourceUri)

        // Optional: configure CropImageView UI (guidelines, aspect ratio, etc.)
        // cropImageView.setAspectRatio(3, 1)           // example
        // cropImageView.setFixedAspectRatio(true)

        findViewById<Button>(R.id.btnCrop).setOnClickListener {
            // request async crop (non-blocking)
            cropImageView.croppedImageAsync()
        }

        // Async callback when crop completes
        cropImageView.setOnCropImageCompleteListener { _, result ->
            if (result.isSuccessful) {
                val croppedBitmap: Bitmap? = result.bitmap
                if (croppedBitmap == null) {
                    Toast.makeText(this, "Crop returned empty bitmap", Toast.LENGTH_SHORT).show()
                    return@setOnCropImageCompleteListener
                }

                // Save on IO, then launch preview on Main
                lifecycleScope.launch(Dispatchers.IO) {
                    var outFile: File? = null
                    try {
                        outFile = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")

                        // Ensure JPEG won't show black for transparency: paint white background if needed
                        val toSave = if (croppedBitmap.hasAlpha()) {
                            val withWhite = createBitmap(croppedBitmap.width, croppedBitmap.height)
                            val c = Canvas(withWhite)
                            c.drawColor(Color.WHITE)
                            c.drawBitmap(croppedBitmap, 0f, 0f, null)
                            withWhite
                        } else {
                            croppedBitmap
                        }

                        FileOutputStream(outFile).use { fos ->
                            toSave.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        }

                        if (toSave !== croppedBitmap) {
                            toSave.recycle()
                        }

                    } catch (ex: Exception) {
                        outFile = null
                    } finally {
                        // Recycle original cropped bitmap on IO thread if still present
                        try { if (!croppedBitmap.isRecycled) croppedBitmap.recycle() } catch (_: Exception) {}
                    }

                    withContext(Dispatchers.Main) {
                        if (outFile == null) {
                            Toast.makeText(this@ImageCropperActivity, "Failed to save cropped image", Toast.LENGTH_SHORT).show()
                        } else {
                            // Launch PreviewActivity with the CROPPED file path
                            val intent = Intent(this@ImageCropperActivity, PreviewActivity::class.java).apply {
                                putExtra(PreviewActivity.EXTRA_IMAGE_PATH, outFile.absolutePath)
                                putExtra(PreviewActivity.EXTRA_USER_ID, userId)
                                putExtra(PreviewActivity.EXTRA_MODE, mode)
                            }
                            startActivity(intent)
                            finish()
                        }
                    }
                }
            } else {
                val ex = result.error
                Toast.makeText(this, "Crop failed: ${ex?.localizedMessage ?: "unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // If you had this helper earlier; keep or implement as needed
    private fun enableEdgeToEdge() {
        // no-op here — keep your actual edge-to-edge implementation if present
    }
}