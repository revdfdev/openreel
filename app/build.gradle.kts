plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mkodekar.openreel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mkodekar.openreel"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // We ship arm first (99% of phones); add x86_64 for emulators/Chromebooks.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // F-Droid builds split per-ABI APKs to keep each download small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // NOTE: libmpv is NOT on Maven. Its prebuilt .so files and MPVLib bindings
    // are added manually -- see README "Wiring libmpv". They live in:
    //   app/src/main/jniLibs/<abi>/libmpv.so (+ libavcodec, etc.)
    //   app/src/main/java/is/xyz/mpv/MPVLib.java  (JNI binding, LGPL, copied in)
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.documentfile)

    // --- Torrent (module 3) ---
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.x86_64)

    // --- Share over local Wi-Fi (module 4) ---
    implementation(libs.nanohttpd)
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
}
