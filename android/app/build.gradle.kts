plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // native player: hardware decoding + HLS
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    // neural super-resolution on the Snapdragon NPU (QNN execution provider, bundles the Qualcomm runtime)
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
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
        ndk { abiFilters += listOf("arm64-v8a") } // the NPU runtime is arm64 only
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
    packaging {
        jniLibs {
            useLegacyPackaging = true // extracted to nativeLibraryDir, where the NPU driver looks for its DSP library
            // keep only the Hexagon generations of current Snapdragon phones (8 Gen 2 = V73, 8 Gen 3 = V75, 8 Elite = V79, next = V81)
            excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu.so", "**/libQnnHtpV68*.so", "**/libQnnHtpV69*.so")
        }
    }
}
