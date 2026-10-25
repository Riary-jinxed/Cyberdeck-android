plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.3"
}

// Netrunner OS's own apps (Photos, then Vidéo, PDF, Fichiers): one jar, one entry point per app
// (Main.kt), run by the system Java of the OS. os/apps/build-deb.sh packages it.
val appVersion = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    // The As//ka bridge, shared with As//ka (aska-desktop) as source.
    sourceSets["main"].kotlin.srcDir("../os-bridge/src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("../os-bridge/src/test/kotlin")
}

// Deck Link's HUD fonts (SIL OFL, see licenses/), so the OS apps look like the rest of the deck.
val fonts = tasks.register<Sync>("fonts") {
    from("../desktop/src/main/resources/font")
    into(layout.buildDirectory.dir("generated/fonts/font"))
}
sourceSets["main"].resources.srcDir(files(layout.buildDirectory.dir("generated/fonts")).builtBy(fonts))

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Vidéo decodes with libVLC (the system's libvlc5), through its Java binding.
    implementation("uk.co.caprica:vlcj:4.8.3")
    // PDF reads and draws its pages with PDFBox (as Deck Link writes its reports).
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dev.netrunner.os.MainKt"
        nativeDistributions {
            packageName = "netrunner-apps"
            packageVersion = appVersion
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
