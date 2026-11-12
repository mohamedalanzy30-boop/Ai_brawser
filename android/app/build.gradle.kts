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
        // CI passes -Pvc=<run number> so every build can be installed over the previous one
        val vc = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "0.1.$vc"
    }
    signingConfigs {
        // fixed key so updates install over previous builds (debug-only key, not a secret)
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
