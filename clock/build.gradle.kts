plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.netrunner.clock"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netrunner.clock"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
    }

    // Same key as the launcher (CI secrets), so each new APK installs over the previous one.
    val keystore = System.getenv("NETRUNNER_KEYSTORE")?.takeIf { file(it).exists() }
    signingConfigs {
        if (keystore != null) {
            create("netrunner") {
                storeFile = file(keystore)
                storePassword = System.getenv("NETRUNNER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NETRUNNER_KEY_ALIAS") ?: "netrunner"
                keyPassword = System.getenv("NETRUNNER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "netrunner" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // QR / barcode scan to stop an alarm (Google Play services UI, no camera permission).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
