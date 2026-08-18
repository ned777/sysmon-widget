// The build file for the ":app" module — this is where the actual Android app
// gets configured: what SDK versions to target, what dependencies (external
// libraries) to pull in, and how to package the final .apk.

plugins {
    // Turn on the two plugins the root build.gradle.kts made available.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Must exactly match the package name used throughout app/src/main/java/...
    // and in AndroidManifest.xml — this becomes part of R.class's package too.
    namespace = "com.sysmonwidget.app"

    // Which version of the Android SDK to COMPILE against — i.e. which APIs
    // (including brand new ones) are allowed to be referenced in the code.
    // This is independent of which Android versions the app can actually RUN
    // on (see minSdk/targetSdk below).
    compileSdk = 34

    defaultConfig {
        // The unique id Google Play (and the OS itself) uses to identify this
        // exact app — no two apps on a phone (or in the Play Store) can share
        // an applicationId. By convention it matches the namespace above,
        // though technically they're allowed to differ.
        applicationId = "com.sysmonwidget.app"

        // The OLDEST Android version this app is allowed to install/run on.
        // API 29 = Android 10. Trying to install on an older phone than this
        // simply won't be offered/allowed. We picked 29 because it's old
        // enough to cover the vast majority of real phones, while still being
        // recent enough to rely on modern APIs without tons of compatibility
        // workarounds.
        minSdk = 29

        // The Android version this app is specifically TESTED and OPTIMIZED
        // for — set this too low and the OS may apply older compatibility
        // behaviors even when running on a much newer phone.
        targetSdk = 34

        // versionCode: a plain integer that must strictly increase with every
        // release — this is what app stores/update systems compare to know a
        // build is "newer". versionName is just the human-readable label
        // (e.g. "1.0", "2.3.1") shown to users; it can be any string.
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Settings specific to "release" builds (as opposed to the default
        // "debug" build type used automatically during development — which is
        // what gradlew assembleDebug / our adb install commands have been
        // using throughout this whole project).
        release {
            // Minification (shrinking + obfuscating the compiled code to make
            // the .apk smaller and harder to reverse-engineer) is turned off
            // here — fine for a personal project, but a published app would
            // usually enable this.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Which version of the Java language spec our code (and any Java-only
        // libraries it depends on) is compiled against.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // The equivalent setting for Kotlin — must line up with the Java
        // versions above, since Kotlin ultimately compiles down to the same
        // JVM bytecode.
        jvmTarget = "17"
    }

    buildFeatures {
        // Enables ViewBinding support project-wide (though this particular
        // project mostly still uses the older, simpler findViewById pattern
        // directly rather than generated Binding classes).
        viewBinding = true
    }
}

dependencies {
    // External libraries this app depends on, each as "group:artifact:version".
    // Gradle downloads these automatically from the repositories declared in
    // settings.gradle.kts.
    implementation("androidx.core:core-ktx:1.13.1")       // Kotlin-friendly extensions over core Android APIs.
    implementation("androidx.appcompat:appcompat:1.7.0")   // Backwards-compatible base classes, incl. AppCompatActivity.
    implementation("com.google.android.material:material:1.12.0") // Material Components (theming, widgets like AlertDialog styling).
}
