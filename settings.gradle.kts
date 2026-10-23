pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CyberpunkLauncher"
include(":app")
include(":clock")
include(":deck")
include(":calc")
include(":files")
include(":agenda")
include(":gallery")
include(":notes")
include(":recorder")
include(":scanner")
include(":weather")
include(":music")
include(":osint")
include(":dates")
