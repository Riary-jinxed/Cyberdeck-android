plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.10.3"
}

// Netrunner OS's own apps (Photos, Vidéo, PDF, Texte, Tableur, Présentation…): one jar, one entry point per app
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
    // Texte reads and writes Word files with Apache POI (OpenDocument is written by hand).
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    // Old Word and PowerPoint files (.doc, .ppt): POI's scratchpad, read only.
    implementation("org.apache.poi:poi-scratchpad:5.5.1")
    // Web draws pages with Chromium, through JCEF; its native part is installed beside the jar
    // (os/apps/build-deb.sh), not in it.
    implementation("me.friwi:jcefmaven:146.0.10")
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
