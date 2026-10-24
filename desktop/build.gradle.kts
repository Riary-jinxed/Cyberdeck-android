import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.3"
}

val appVersion = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    // The sync protocol is shared with the launcher, which compiles the same sources.
    sourceSets["main"].kotlin.srcDir("../sync-core/src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("../sync-core/src/test/kotlin")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dev.netrunner.decklink.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Deck Link"
            packageVersion = appVersion
            description = "Synchro du kit Netrunner avec le PC"
            vendor = "Netrunner"
            // The Deck Probe endpoint (Fleet.kt) runs on the JDK's built-in HTTP server.
            modules("jdk.httpserver")
            windows {
                // Per-user install with a folder chooser: the sync folder lives next to the app,
                // so the install folder must stay writable (not Program Files).
                dirChooser = true
                perUserInstall = true
                menu = true
                menuGroup = "Netrunner"
                shortcut = true
                upgradeUuid = "6f1c3b8e-2d4a-4f6e-9a51-7c0d2e8b4a93"
            }
            linux {
                packageName = "deck-link"
                menuGroup = "Network"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
