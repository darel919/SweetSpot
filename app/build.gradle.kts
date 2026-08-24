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

        versionCode = 1
        versionName = "0.1.0"
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
        buildConfig = false
        compose = false
        shaders = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}
