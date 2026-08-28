plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.darelisme.sweetspot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.darelisme.sweetspot"
        minSdk = 28
        targetSdk = 36

        ndk {
            abiFilters += "armeabi-v7a"
        }

        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SWEETSPOT_BUILD_ID", "\"${providers.environmentVariable("SWEETSPOT_BUILD_ID").orElse(providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }).get()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = false
        buildConfig = true
        compose = false
        shaders = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidsvg)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.webrtc.android)
    testImplementation(libs.junit)
}
