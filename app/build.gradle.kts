plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "kz.troechka.helper"
    compileSdk = 35
    defaultConfig {
        applicationId = "kz.troechka.helper"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-quickscan"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
