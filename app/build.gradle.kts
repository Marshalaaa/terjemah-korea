plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.terjemah.korea"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.terjemah.korea"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // OCR Korea (Hangul), model ikut di dalam APK, jalan offline
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Terjemahan on-device Korea -> Indonesia (model diunduh sekali, lalu offline)
    implementation("com.google.mlkit:translate:17.0.3")
}
