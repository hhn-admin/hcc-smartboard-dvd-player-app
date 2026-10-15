plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.captureboard.hdmiin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.captureboard.hdmiin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Extract native libraries on install. More reliable on older smart-board
            // images than loading uncompressed JNI straight from the APK.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // libusb/libuvc UVC stack, published on Maven Central. See README.
    implementation("com.herohan:UVCAndroid:1.0.13")

    testImplementation("junit:junit:4.13.2")
}
