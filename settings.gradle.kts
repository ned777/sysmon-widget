// This is the very first Gradle file read when the project loads (before the
// root build.gradle.kts, before app/build.gradle.kts). Its job is telling
// Gradle which MODULES make up this project, and where to download
// dependencies/plugins from. A larger app might have several modules (e.g. an
// ":app" module plus a shared ":core" library module); ours only has one.

pluginManagement {
    // Where Gradle looks for PLUGINS (like the Android Gradle Plugin and the
    // Kotlin plugin declared in the root build.gradle.kts).
    repositories {
        google()            // Google's own repository — required for all Android tooling.
        mavenCentral()       // The main general-purpose Java/Kotlin library repository.
        gradlePluginPortal() // Gradle's own plugin marketplace.
    }
}
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS forces every module to use ONLY the repositories
    // listed here, instead of letting individual modules each declare their
    // own — keeping dependency sources centralized and predictable.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The project's display name inside Gradle/Android Studio's project tree.
rootProject.name = "SysMonWidget"

// Declares that there's one module, ":app", living in the "app/" folder —
// this is what makes Android Studio show an "app" module you can build/run.
include(":app")
