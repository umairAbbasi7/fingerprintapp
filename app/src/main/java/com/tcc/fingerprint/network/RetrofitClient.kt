package com.tcc.fingerprint.network

import com.tcc.fingerprint.data.SharedPreferencesManager
import com.tcc.fingerprint.utils.Constants
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RetrofitClient(private val prefsManager: SharedPreferencesManager) {
    
    private fun createOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        
        val customInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            response
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(customInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(0, TimeUnit.SECONDS)  // Unlimited connect timeout
            .readTimeout(0, TimeUnit.SECONDS)     // Unlimited read timeout
            .writeTimeout(0, TimeUnit.SECONDS)    // Unlimited write timeout
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(prefsManager.apiBaseUrl)
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    fun updateBaseUrl(newBaseUrl: String) {
        // Note: This would require recreating the Retrofit instance
        // For now, we'll use the SharedPreferencesManager to update the URL
        prefsManager.apiBaseUrl = newBaseUrl
    }

    fun updateTimeout(newTimeout: Long) {
        prefsManager.apiTimeout = newTimeout
    }
} 