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

rootProject.name = "latent"

include(":app")

// The camera's logic lives in its own build so it compiles and tests with
// nothing but Maven Central: API clients, prompt/model stores, the retry
// policy. The Android shell substitutes it in as io.github.jeromekai.latent:core.
includeBuild("core")
