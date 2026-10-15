plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// MDM updates identify the app by applicationId (com.hcc.dvdplayer) and require a
// higher versionCode signed with the same certificate. Do not change applicationId.
// Bump both version fields together, then tag the commit v<hdmiVersionName>.
val hdmiVersionCode = 4
val hdmiVersionName = "1.0.3"

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.hcc.dvdplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hcc.dvdplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = hdmiVersionCode
        versionName = hdmiVersionName
    }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off. UVCAndroid loads JNI and reflective callback classes;
            // shrinking that stack is what breaks preview on devices. The keep
            // rules in proguard-rules.pro are there if minify is turned on later.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

base {
    archivesName.set("dvd-player-$hdmiVersionName")
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
