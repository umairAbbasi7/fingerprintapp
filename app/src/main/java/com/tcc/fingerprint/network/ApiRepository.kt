package com.tcc.fingerprint.network

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tcc.fingerprint.data.SharedPreferencesManager
import com.tcc.fingerprint.utils.Constants
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class ApiRepository(
    private val context: Context,
    private val prefsManager: SharedPreferencesManager,
    private val retrofitClient: RetrofitClient
) {
    private val apiService = retrofitClient.apiService
    private val tag = "ApiRepository"
    
    // Status callback for detailed updates
    var onStatusUpdate: ((String) -> Unit)? = null

    suspend fun registerFingerprint(
        userId: String,
        bitmap: Bitmap
    ): Result<ApiResponse> {
        return executeWithRetry {
            onStatusUpdate?.invoke("Preparing image for upload...")
            
            val imageFile = saveBitmapToFile(bitmap, "register_${System.currentTimeMillis()}.jpg")
            
            val imageRequestBody = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val imagePart = MultipartBody.Part.createFormData("image", imageFile.name, imageRequestBody)
            val uidBody = userId.toRequestBody("text/plain".toMediaTypeOrNull())
            
            onStatusUpdate?.invoke("Sending registration request...")
            
            val apiStartTime = System.currentTimeMillis()
            val response = apiService.registerFingerprint(uidBody, imagePart)
            val apiResponseTime = System.currentTimeMillis() - apiStartTime
            onStatusUpdate?.invoke("Server responded in ${apiResponseTime}ms")

            val code = response.code()
            when (code) {
                200 -> {
                    onStatusUpdate?.invoke("Processing server response...")
                    val body = response.body()
                    val msg = body?.message ?: "Registration completed successfully"
                    ApiResponse(
                        success = true,
                        message = msg,
                        timestamp = System.currentTimeMillis(),
                        code = 200,
                        durationMs = apiResponseTime
                    )
                }
                422 -> {
                    ApiResponse(
                        success = false,
                        message = "Registration failed",
                        timestamp = System.currentTimeMillis(),
                        code = 422,
                        durationMs = apiResponseTime
                    )
                }
                else -> {
                    val err = response.errorBody()?.string() ?: "Registration failed"
                    ApiResponse(
                        success = false,
                        message = err,
                        timestamp = System.currentTimeMillis(),
                        code = code,
                        durationMs = apiResponseTime
                    )
                }
            }
        }
    }

    suspend fun verifyFingerprint(
        userId: String,
        bitmap: Bitmap
    ): Result<ApiResponse> {
        return executeWithRetry {
            onStatusUpdate?.invoke("Preparing image for verification...")
            
            val imageFile = saveBitmapToFile(bitmap, "verify_${System.currentTimeMillis()}.jpg")
            
            val imageRequestBody = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val imagePart = MultipartBody.Part.createFormData("image", imageFile.name, imageRequestBody)
            val uidBody = userId.toRequestBody("text/plain".toMediaTypeOrNull())
            
            onStatusUpdate?.invoke("Sending verification request...")
            
            val apiStartTime = System.currentTimeMillis()
            val response = apiService.verifyFingerprint(uidBody, imagePart)
            val apiResponseTime = System.currentTimeMillis() - apiStartTime
            onStatusUpdate?.invoke("Server responded in ${apiResponseTime}ms")

            val code = response.code()
            if (code == 200) {
                onStatusUpdate?.invoke("Processing server response...")
                // Treat HTTP 200 as verification success regardless of body content
                ApiResponse(
                    success = true,
                    message = "Fingerprint verified successfully",
                    timestamp = System.currentTimeMillis(),
                    code = 200,
                    durationMs = apiResponseTime,
                    result = "verified",
                    matchScore = null
                )
            } else if (code == 422) {
                ApiResponse(
                    success = false,
                    message = "Fingerprint verification failed",
                    timestamp = System.currentTimeMillis(),
                    code = 422,
                    durationMs = apiResponseTime,
                    result = "not_verified"
                )
            } else {
                val err = response.errorBody()?.string() ?: "Verification failed"
                ApiResponse(
                    success = false,
                    message = err,
                    timestamp = System.currentTimeMillis(),
                    code = code,
                    durationMs = apiResponseTime,
                    result = "not_verified"
                )
            }
        }
    }

    suspend fun enrollFingerprint(
        userId: String,
        bitmap: Bitmap
    ): Result<ApiResponse> {
        return executeWithRetry {
            onStatusUpdate?.invoke("Preparing image for enrollment...")
            val imageFile = saveBitmapToFile(bitmap, "enroll_${System.currentTimeMillis()}.jpg")
            val imageRequestBody = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val imagePart = MultipartBody.Part.createFormData("image", imageFile.name, imageRequestBody)
            
            val uidBody = userId.toRequestBody("text/plain".toMediaTypeOrNull())
            
            onStatusUpdate?.invoke("Sending enrollment request...")
            val startTime = System.currentTimeMillis()
            val response = apiService.enrollFingerprint(uidBody, imagePart)
            val responseTime = System.currentTimeMillis() - startTime
            
            onStatusUpdate?.invoke("Server responded in ${responseTime}ms")
            
            if (response.isSuccessful) {
                onStatusUpdate?.invoke("Processing server response...")
                response.body() ?: throw Exception("Empty response body")
            } else {
                throw Exception("Enrollment failed: ${response.code()} (${responseTime}ms)")
            }
        }
    }

    suspend fun checkServerStatus(): Result<StatusResponse> {
        return executeWithRetry {
            val response = apiService.getStatus()
            if (response.isSuccessful) {
                response.body() ?: throw Exception("Empty status response")
            } else {
                throw Exception("Status check failed: ${response.code()}")
            }
        }
    }

    suspend fun healthCheck(): Result<HealthResponse> {
        return executeWithRetry {
            val response = apiService.healthCheck()
            if (response.isSuccessful) {
                response.body() ?: throw Exception("Empty health response")
            } else {
                throw Exception("Health check failed: ${response.code()}")
            }
        }
    }

    private suspend fun <T> executeWithRetry(
        maxRetries: Int = prefsManager.maxRetries,
        initialDelay: Long = 0L,  // No delay for testing
        maxDelay: Long = 0L,      // No delay for testing
        factor: Double = 1.0,     // No exponential backoff
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                if (attempt > 0) {
                    onStatusUpdate?.invoke("Retry attempt ${attempt + 1}/$maxRetries...")
                }
                return Result.success(block())
            } catch (e: Exception) {
                Log.w(tag, "API call failed (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                
                if (attempt == maxRetries - 1) {
                    onStatusUpdate?.invoke("All retry attempts failed")
                    return Result.failure(e)
                }
                
                onStatusUpdate?.invoke("Retrying immediately...")
                // No delay for testing
                currentDelay = 0L
            }
        }
        return Result.failure(Exception("Max retries exceeded"))
    }

    private fun saveBitmapToFile(bitmap: Bitmap, filename: String): File {
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, Constants.IMAGE_QUALITY, out)
        }
        return file
    }

    fun cleanupCache() {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("register_") || 
                    file.name.startsWith("verify_") || 
                    file.name.startsWith("enroll_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error cleaning cache: ${e.message}")
        }
    }
} 