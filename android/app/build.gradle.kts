plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}

android {
    namespace = "com.aib.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aib.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // wasm / onnx must be stored uncompressed so they can be streamed from assets
    androidResources { noCompress += listOf("wasm", "onnx", "mjs") }
}
