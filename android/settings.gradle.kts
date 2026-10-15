pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tablero"

// core: scraping, parsing, derive, ingest, queries. Plain JVM, so its tests
// run against the shared fixtures with no Android SDK or emulator.
include(":core")
include(":app")
