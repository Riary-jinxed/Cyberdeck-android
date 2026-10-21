import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// As//ka's neural voice runtime (sherpa-onnx, Apache 2.0) is not on Maven Central: its release
// AAR is fetched once from GitHub and checked against a known hash.
val sherpaVersion = "1.13.7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val part = File(sherpaAar.path + ".part")
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
        .toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val sha = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
    check(sha == "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8") { "sherpa-onnx AAR: unexpected hash $sha" }
    part.renameTo(sherpaAar)
}

android {
    namespace = "dev.netrunner.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netrunner.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = 27
        versionName = "1.3.1"

        // As//ka's model runtime ships native code: phones only, which keeps the APK small.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Stable key from the CI secrets, so each new APK installs over the previous one (and the
    // built-in updater works). Without the secrets, builds fall back to the debug key.
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
            // Off: keeps stack traces readable while the launcher is still moving fast.
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
    packaging {
        jniLibs {
            // The neural voice only needs the JNI bridge and onnxruntime.
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/module-info.class",
            )
        }
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("com.google.zxing:core:3.5.3")
    // Barcode / QR scanning for the equipment inventory (Google Play services UI, no camera permission).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // As//ka: on-device language model runtime (Gemma, Qwen… in .litertlm format).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    // As//ka's neural voice (Piper models through sherpa-onnx), and the unpacking of the voice archive.
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.27.1")
}
