plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.netrunner.notes"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netrunner.notes"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
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
    implementation(project(":deck"))
}
