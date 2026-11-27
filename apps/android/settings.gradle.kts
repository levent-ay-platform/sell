pluginManagement {
    repositories {
        google()
        // Prefer Google's public Maven Central mirror to avoid Maven Central 429 rate limits in hosted CI.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Prefer Google's public Maven Central mirror to avoid Maven Central 429 rate limits in hosted CI.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Malak"
include(":app")
