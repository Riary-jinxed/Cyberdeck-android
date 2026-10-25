import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.3"
}

val appVersion = "0.1.3"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    // The bridge to Netrunner OS's own apps (OsApps.kt), shared with them as source.
    sourceSets["main"].kotlin.srcDir("../os-bridge/src/main/kotlin")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Keyboard and mouse activity anywhere on the desktop (counts only, never which key).
    implementation("com.github.kwhat:jnativehook:2.2.2")
    // The window in front and the battery, on Windows.
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dev.netrunner.aska.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Aska"
            packageVersion = appVersion
            description = "As//ka, la compagne du deck, sur le bureau"
            vendor = "Netrunner"
            modules("java.net.http", "java.management", "jdk.management")
            windows {
                perUserInstall = true
                // Its folder can be chosen by hand; Deck Link installs it next to itself (Apps.kt).
                dirChooser = true
                menu = true
                menuGroup = "Netrunner"
                shortcut = true
                upgradeUuid = "b4e2a7c1-5d3f-4a8e-9c62-1f7e0a3d9b54"
            }
            linux {
                packageName = "aska"
                menuGroup = "Utility"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
