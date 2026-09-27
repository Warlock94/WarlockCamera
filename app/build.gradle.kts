plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.warlock.rokidcamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.warlock.rokidcamera"
        minSdk = 28
        targetSdk = 32
        versionCode = 37
        versionName = "2.6.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
