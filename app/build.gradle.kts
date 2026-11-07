import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.handgame.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.handgame.prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-prototype"
        ndk {
            // MediaPipe and Filament ship native libs; phones are arm64 (keep x86_64 for emulators).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Prototype: sign release with the debug key so it can be side-loaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The MediaPipe model must stay uncompressed so it can be memory-mapped.
        noCompress += listOf("task", "tflite")
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The MediaPipe hand model is fetched at build time instead of being committed to git.
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val downloadHandModel by tasks.registering {
    val out = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")
    outputs.file(out)
    doLast {
        val f = out.asFile
        if (!f.exists() || f.length() == 0L) {
            f.parentFile.mkdirs()
            logger.lifecycle("Downloading MediaPipe hand model…")
            URI(handModelUrl).toURL().openStream().use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

    val camerax = "1.5.3"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.33")

    val filament = "1.75.1"
    implementation("com.google.android.filament:filament-android:$filament")
    // Runtime material compiler: lets us author all shaders as Kotlin strings, no offline matc step.
    implementation("com.google.android.filament:filamat-android:$filament")

    testImplementation("junit:junit:4.13.2")
}
