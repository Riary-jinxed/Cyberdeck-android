plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// llama.cpp (MIT), for the GGUF models: its source at a fixed release, fetched once and built with
// the app by CMake (src/main/cpp).
val llamaTag = "b6000"
val llamaDir = layout.buildDirectory.dir("llama.cpp-$llamaTag").get().asFile
// A task, not configuration code: the other apps' builds configure this project too, and must not download it.
val fetchLlama by tasks.registering {
    outputs.dir(llamaDir)
    doLast {
        if (File(llamaDir, "CMakeLists.txt").exists()) return@doLast
        val archive = File(llamaDir.parentFile, "llama.cpp-$llamaTag.tar.gz")
        archive.parentFile.mkdirs()
        uri("https://github.com/ggml-org/llama.cpp/archive/refs/tags/$llamaTag.tar.gz")
            .toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        project.copy {
            from(project.tarTree(project.resources.gzip(archive)))
            into(llamaDir.parentFile)
        }
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchLlama)
}

android {
    namespace = "dev.netrunner.dates"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netrunner.dates"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        // The model runtimes ship native code: phones only, which keeps the APK small.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DLLAMA_SRC=${llamaDir.path}", "-DANDROID_STL=c++_static")
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/module-info.class")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":deck"))
    // On-device language model runtime, the same as As//ka's in the launcher (.litertlm files).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}
