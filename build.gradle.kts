plugins {
    // Versions are declared here and applied in submodules via `apply false`.
    // Update these when upgrading the KMP or Android Gradle Plugin version.
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Only for parsing firmware-package manifests and the release index
    // (shared/firmware/): third-party JSON is hostile input and does not
    // get a hand-rolled parser.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}

// Reproducible-build hygiene (carried over from reticulum-mobile-app):
// archive tasks run with file timestamps zeroed and entries sorted so two
// builds of the same source tree produce byte-identical APKs.
allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
