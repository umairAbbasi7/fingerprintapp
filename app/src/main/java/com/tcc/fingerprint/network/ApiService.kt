package com.tcc.fingerprint.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    
    @Multipart
    @POST("register")
    suspend fun registerFingerprint(
        @Part("uid") uid: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<ApiResponse>

    @Multipart
    @POST("verify")
    suspend fun verifyFingerprint(
        @Part("uid") uid: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<VerificationResponse>

    @Multipart
    @POST("enroll")
    suspend fun enrollFingerprint(
        @Part("uid") uid: RequestBody,
        @Part image: MultipartBody.Part
    ): Response<ApiResponse>

    @GET("status")
    suspend fun getStatus(): Response<StatusResponse>

    @POST("health")
    suspend fun healthCheck(): Response<HealthResponse>
}

data class ApiResponse(
    val success: Boolean,
    val message: String,
    val data: ApiData? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // Augmented fields for UI summaries
    val code: Int? = null,
    val durationMs: Long? = null,
    val result: String? = null,
    val matchScore: Float? = null
)

// Alternative response format that might be used by the API
data class SimpleApiResponse(
    val status: String? = null,
    val message: String? = null,
    val result: String? = null,
    val error: String? = null
)

// Verification response format
data class VerificationResponse(
    val status: String? = null
)

// Generic response that can handle any format
data class GenericApiResponse(
    val success: Boolean = true,
    val message: String = "Operation completed",
    val rawResponse: String? = null
)

data class ApiData(
    val userId: String? = null,
    val fingerprintId: String? = null,
    val confidence: Float = 0f,
    val quality: Float = 0f,
    val matchScore: Float = 0f,
    val processingTime: Long = 0L
)

data class StatusResponse(
    val status: String,
    val version: String,
    val uptime: Long,
    val activeConnections: Int
)

data class HealthResponse(
    val healthy: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
) 