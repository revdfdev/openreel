import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing material lives in keystore.properties, which is gitignored. Release
// builds fall back to unsigned if it is absent, so a fresh clone still builds.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mkodekar.openreel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mkodekar.openreel"
        minSdk = 26   // libmpv AAR requires API 26+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed so installs can be updated in place; a debug-signed APK
            // cannot be upgraded by a release one without uninstalling first.
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    // libmpv ships as a prebuilt AAR (dev.jdtech.mpv:libmpv) carrying
    // libmpv.so + the FFmpeg family for all four ABIs, so no NDK build and no
    // hand-copied jniLibs are needed. Both libmpv and libtorrent4j bundle
    // libc++_shared.so, so take the first to avoid a duplicate-file failure.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/libc++_shared.so"
        }
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    // Compose foundation + animation directly: the UI is hand-drawn to match MX,
    // so Material3 is deliberately not a dependency.
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    // --- Playback engine: libmpv = FFmpeg + libass (module 1) ---
    implementation(libs.libmpv)

    // --- Torrent (module 3) ---
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4jArm64)
    implementation(libs.libtorrent4jArm)
    implementation(libs.libtorrent4jX64)

    // --- Share over local Wi-Fi (module 4) ---
    implementation(libs.nanohttpd)
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
}
