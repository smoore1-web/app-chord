plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stm.chordear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stm.chordear"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // fixed key so each new APK installs as an update over the last one
    signingConfigs {
        getByName("debug") {
            storeFile = file("chordear.keystore")
            storePassword = "chordear"
            keyAlias = "chordear"
            keyPassword = "chordear"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
