plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.smartblindstick"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.smartblindstick"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read secrets from local.properties (gitignored)
        val localProperties = java.util.Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${localProperties.getProperty("TELEGRAM_BOT_TOKEN", "")}\"")
        buildConfigField("String", "TELEGRAM_CHAT_ID", "\"${localProperties.getProperty("TELEGRAM_CHAT_ID", "")}\"")
        buildConfigField("String", "NVIDIA_API_KEY", "\"${localProperties.getProperty("NVIDIA_API_KEY", "")}\"")
        buildConfigField("String", "NVIDIA_META_API_KEY", "\"${localProperties.getProperty("NVIDIA_META_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX core library
    val cameraxVersion = "1.3.0-alpha04"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Face Detection
    implementation("com.google.mlkit:face-detection:16.1.5")
    
    // ML Kit Object Detection
    implementation("com.google.mlkit:object-detection:17.0.0")
    implementation("com.google.mlkit:object-detection-custom:17.0.0")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    // ML Kit Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // OkHttp for Telegram API requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for simple JSON parsing (useful for face embeddings if needed)
    implementation("com.google.code.gson:gson:2.10.1")

    // Room components
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
}