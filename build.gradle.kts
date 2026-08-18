// The ROOT build file — settings shared across every module in the project
// (we only have one module, ":app", but larger projects might have several).
// This file mostly just declares which Gradle PLUGINS are available to use,
// without actually turning them on yet (`apply false`) — each module (like
// app/build.gradle.kts) then opts into the specific plugins it actually needs.

plugins {
    // The Android Gradle Plugin — teaches Gradle how to build .apk files,
    // run Android-specific tasks, merge manifests/resources, etc.
    id("com.android.application") version "8.5.2" apply false

    // Teaches Gradle how to compile Kotlin source files for Android.
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
