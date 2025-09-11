# Keep Retrofit/OkHttp models and annotations
-keepattributes Signature, *Annotation*
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Glide (if used)
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# Remove debug logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}