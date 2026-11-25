plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.floatingdiscord"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.floatingdiscord"
        minSdk = 26
        targetSdk = 34
        // naik otomatis tiap build di GitHub Actions, supaya selalu dianggap update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "1"}"
    }

    // Keystore tetap: tanda tangan APK selalu sama, jadi bisa di-install sebagai update
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.jks")
            storePassword = "floatingdiscord"
            keyAlias = "floating"
            keyPassword = "floatingdiscord"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
