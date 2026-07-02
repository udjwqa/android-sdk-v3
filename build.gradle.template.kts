// SDK v4.0.0 (2026-07-02): Template build.gradle.kts для модуля app в SDK v4 интеграции.
// Скопировать в app/build.gradle.kts клиентского APK + adapt namespace/applicationId.
//
// Главное: BuildTypes release с минификацией обязателен, иначе R8 не работает
// и APK декомпилится за 2 минуты через jadx.
//
// v4 breaking changes:
// - Play Integrity Standard API (не Classic) — конструктору AppClient нужен
//   `cloudProjectNumber = <ваш GCP project number>` (см. Play Console → App integrity)
// - POST wire protocol — sensitive params (integrity_token, sid) в headers
// - Default path = "/init" (был "/football")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")  // если используется Firebase
}

android {
    namespace = "com.example.yourapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.yourapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // F8 (2026-06-22): ОБЯЗАТЕЛЬНО для anti-decompile
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подпись release
            signingConfig = signingConfigs.getByName("release")
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
    // OkHttp 4.x для AppClient (HTTP client + cert pinning F7)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Play Integrity API (Standard, не Classic — Classic deprecated с 2025)
    implementation("com.google.android.play:integrity:1.4.0")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // (опц.) Chrome Custom Tabs для open offer URL
    implementation("androidx.browser:browser:1.7.0")

    // (опц.) Firebase Remote Config — если хотите fetch endpoint URL dynamically
    // (см. AppClientBuilder.kt). Позволяет менять endpoint без rebuild APK.
    // implementation("com.google.firebase:firebase-config:22.0.0")
}
