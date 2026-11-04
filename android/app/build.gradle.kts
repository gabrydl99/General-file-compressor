plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gabrydl99.gfc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gabrydl99.gfc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // The release key is never stored in the repository. On GitHub it comes from the
    // repository secrets GFC_KEYSTORE_B64 / GFC_KEYSTORE_PASSWORD (see android/NEXT_STEPS.md);
    // without them the release APK is signed with the debug key.
    val ks = System.getenv("GFC_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ks != null) {
            create("release") {
                storeFile = ks
                storePassword = System.getenv("GFC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GFC_KEY_ALIAS") ?: "gfc"
                keyPassword = System.getenv("GFC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // phones: 64-bit and older 32-bit ARM
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        debug {
            // the emulator used for tests is x86_64
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }

    packaging {
        // FFmpeg ships as an executable named libffmpeg.so; it has to be
        // extracted on install so the app can run it.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
