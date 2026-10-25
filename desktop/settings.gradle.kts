// Optional Maven Central mirror (-PmavenMirror=https://… or a local http:// cache), tried first when set.
val mirror: String? = providers.gradleProperty("mavenMirror").orNull

pluginManagement {
    val mirror: String? = providers.gradleProperty("mavenMirror").orNull
    repositories {
        if (mirror != null) maven(mirror) { isAllowInsecureProtocol = mirror.startsWith("http://") }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        if (mirror != null) maven(mirror) { isAllowInsecureProtocol = mirror.startsWith("http://") }
        mavenCentral()
        google()
    }
}
rootProject.name = "DeckLink"
