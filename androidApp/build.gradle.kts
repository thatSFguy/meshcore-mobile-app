plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Derive `(versionName, versionCode)` from the git tag when HEAD sits
 * exactly on an `android-v*` tag (same scheme as reticulum-mobile-app):
 * `android-v1.2.3` → ("1.2.3", 10203). CI `-PversionName`/`-PversionCode`
 * always win; otherwise a local build gets `0.0.0-dev`.
 */
fun gitDerivedVersion(): Pair<String, Int>? {
    val tag = runCatching {
        val proc = ProcessBuilder(
            "git", "describe", "--tags", "--exact-match", "--match", "android-v*",
        ).directory(rootDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0) out else null
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

    val m = Regex("""android-v(\d+)\.(\d+)\.(\d+)""").matchEntire(tag) ?: return null
    val (maj, min, pat) = m.destructured
    return "$maj.$min.$pat" to (maj.toInt() * 10_000 + min.toInt() * 100 + pat.toInt())
}

android {
    namespace = "io.github.thatsfguy.meshcore.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.thatsfguy.meshcore.native"
        minSdk = 26
        targetSdk = 34
        val gitVersion = gitDerivedVersion()
        versionName = (project.findProperty("versionName") as? String)
            ?: gitVersion?.first
            ?: "0.0.0-dev"
        versionCode = (project.findProperty("versionCode") as? String)?.toInt()
            ?: gitVersion?.second
            ?: 1
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    if (releaseKeystore != null) {
        val storePass = System.getenv("RELEASE_STORE_PASSWORD")
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = storePass
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?.takeIf { it.isNotEmpty() }
                    ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Distinct applicationId so a debug build installs side-by-side
        // with a release install.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // Reproducible-build hygiene (see root build.gradle.kts).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    // Export Room schemas for diffing across migrations.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // QR code generation + scanner Activity (contact/channel/community QR).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Node map (SCOPE.md): osmdroid renders OSM tiles without Google Play
    // Services. The only outbound-HTTP feature in the app — tiles are
    // lazy-loaded and cached on disk by osmdroid itself.
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // SQLCipher — the Room DB is encrypted at rest with a key sealed in
    // the Android Keystore (2026-07-31 security review, accepted-risk
    // item #1). Adds ~4 MB of native libs; the tradeoff is deliberate:
    // message history, contacts and GPS were previously cleartext SQLite
    // protected only by file-based encryption.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
