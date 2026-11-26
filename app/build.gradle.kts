plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Diatur lewat -PappVersionName=1.2.3 -PappVersionCode=12 (dipakai oleh GitHub Actions)
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

// build-natives.sh menulis 24 di sini bila pustaka native tidak bisa dibuat untuk Android 6 (API 23).
val nativeMinSdk: Int? = file("src/main/jniLibs/min-sdk.txt").takeIf { it.exists() }
    ?.readText()?.trim()?.toIntOrNull()

android {
    namespace = "com.linvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linvpn.app"
        minSdk = nativeMinSdk ?: 23 // Android 6.0
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Keystore disuplai lewat environment (dibuat otomatis oleh workflow bila belum ada).
    // Tanpa keystore (build lokal) otomatis memakai debug key.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Opsional: ./gradlew assembleRelease -Pminify=true (aturan keep ada di proguard-rules.pro)
            val minify = (findProperty("minify") as String?) == "true"
            isMinifyEnabled = minify
            isShrinkResources = minify
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Satu APK per arsitektur (32-bit & 64-bit) + satu APK universal.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
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
        compose = true
    }

    packaging {
        jniLibs {
            // Binary dnstt & xray dijalankan sebagai proses, jadi harus diekstrak ke disk.
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libxray.so", "**/libdnstt.so")
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**", "/META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Core SSH-2 (fork JSch yang masih dirawat: curve25519, AES-GCM/CTR, SHA-2, ed25519, dst).
    // Patch 2.28.x terbaru diambil saat build; naikkan ke versi lain bila perlu.
    implementation("com.github.mwiede:jsch:2.28.+")
}
