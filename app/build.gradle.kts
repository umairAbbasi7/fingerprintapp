import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.compose.compiler)
}

android {
    signingConfigs {
        create("release") {
            val home = System.getProperty("user.home")
            storeFile = file("$home/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "AndroidDebugKey"
            keyPassword = "android"
        }
    }
    namespace = "com.tcc.fingerprint"
    compileSdk = 35

    // Version management
    val versionNameProp: String = project.findProperty("versionName") as String? ?: "1.2.0"
    val versionCodeProp: Int = (project.findProperty("versionCode") as String?)?.toInt() ?: 112

    defaultConfig {
        applicationId = "com.tcc.fingerprint"
        minSdk = 24
        targetSdk = 33
        versionCode = versionCodeProp
        versionName = versionNameProp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            // Custom APK naming for release builds
            setProperty("archivesBaseName", "TCC-Fingerprint-v${versionNameProp}-RELEASE")
        }
        debug {
            isDebuggable = true

            // Custom APK naming for debug builds
            setProperty("archivesBaseName", "TCC-Fingerprint-v${versionNameProp}-DEBUG")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }


}

// Professional Fingerprint Capture System
// Task to bump patch version and versionCode in version.properties
fun bumpPatch(ver: String): String {
    val parts = ver.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return listOf(major, minor, patch + 1).joinToString(".")
}

tasks.register("bumpVersionPatch") {
    group = "versioning"
    description = "Bump patch version and versionCode in version.properties"
    doLast {
        val versionPropsFile = rootProject.file("version.properties")
        val props = Properties().apply {
            if (versionPropsFile.exists()) FileInputStream(versionPropsFile).use { load(it) }
        }
        val currentName = props.getProperty("versionName") ?: "1.1.1"
        val currentCode = (props.getProperty("versionCode") ?: "111").toInt()
        val newName = bumpPatch(currentName)
        val newCode = currentCode + 1
        props.setProperty("versionName", newName)
        props.setProperty("versionCode", newCode.toString())
        FileOutputStream(versionPropsFile).use { props.store(it, "Auto-bumped by Gradle task") }
        println("Version bumped: $currentName ($currentCode) -> $newName ($newCode)")
    }
}

dependencies {
    // Core Android dependencies
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")

    // Network and API
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Lifecycle and ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.1")

    // UI Components
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Permissions
    implementation("com.guolindev.permissionx:permissionx:1.8.1")

    // Image processing
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // OpenCV for fingerprint processing
    implementation(project(":opencv"))

    // TensorFlow Lite for detection
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.5.0")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.5.0")

    // ONNX Runtime (fallback backend)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    //image cropper
    implementation("com.vanniktech:android-image-cropper:4.6.0")
    implementation(libs.androidx.activity)

    // Testing
    testImplementation("junit:junit:4.13.2")

    // Robolectric for Android framework testing
    testImplementation("org.robolectric:robolectric:4.11.1")

    // Mockito for mocking
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")

    // Coroutines testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Android testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")

    // UI testing
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")

    // Fragment testing
    androidTestImplementation("androidx.fragment:fragment-testing:1.6.2")

    // Architecture components testing
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation(kotlin("test"))

    //camera
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    //compose
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.material:material:1.9.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.0")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.graphics)
}