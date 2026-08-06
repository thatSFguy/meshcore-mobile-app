import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    // No `jvmToolchain(17)` pin — run on whatever JDK (>=17) is present and
    // pin the *output* bytecode to 17 (same F-Droid-friendly setup as
    // reticulum-mobile-app).
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    // iOS: linker-clean-stubs milestone, mirroring reticulum-mobile-app's
    // Phase 1. Every commonMain expect has an iosMain actual so the
    // framework links on a Mac; crypto/BLE actuals throw until the iOS
    // phase lands (CryptoKit bridge, CoreBluetooth).
    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        // Per-target -L pointing at the slice of libMeshCoreCrypto.a
        // that buildIosCryptoBridge produces. It exports the mch_*
        // functions wrapping CryptoKit's Ed25519 surface, which is what
        // lets iOS verify advert signatures at all (see
        // shared/iosCryptoBridge/).
        //
        // binaries.all, NOT just framework: the test executable behind
        // iosSimulatorArm64Test needs the same search path. Without it
        // the framework links fine and linkDebugTestIosSimulatorArm64
        // fails with "library 'MeshCoreCrypto' not found" — a trap the
        // sibling repo hit the moment it added that test task, and this
        // repo's CI runs that task from the start.
        val cryptoBridgeLibDir = layout.buildDirectory
            .dir("iosCryptoBridge/${target.name}")
            .get().asFile.absolutePath
        target.binaries.all {
            linkerOpts("-L$cryptoBridgeLibDir")
        }
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
        target.compilations.getByName("main").cinterops {
            // Declarations are inlined in the def file; the static
            // library is built separately by buildIosCryptoBridge
            // (macOS-only) and found via the -L above.
            create("meshcorecrypto") {
                defFile(project.file("src/nativeInterop/cinterop/meshcorecrypto.def"))
                packageName("io.github.thatsfguy.meshcore.crypto.cinterop")
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
        val androidMain by getting {
            dependencies {
                // Bouncy Castle for Ed25519 (advert verify, identity keygen)
                implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

android {
    namespace = "io.github.thatsfguy.meshcore.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26  // Android 8.0 — BLE APIs stable
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Compile shared/iosCryptoBridge/MeshCoreCrypto.swift into per-target
// static libraries. macOS-only — `swiftc` ships with Xcode. Off macOS
// this task fails when executed, but it is only ever a dependency of
// the iOS link tasks, which cannot run there either.
val buildIosCryptoBridge = tasks.register<Exec>("buildIosCryptoBridge") {
    description = "Compile the CryptoKit Swift wrapper to per-target static libraries."
    group = "build"
    workingDir = project.file("iosCryptoBridge")
    commandLine = listOf("bash", "build.sh")
    inputs.file("iosCryptoBridge/MeshCoreCrypto.swift")
    inputs.file("iosCryptoBridge/build.sh")
    outputs.dir(layout.buildDirectory.dir("iosCryptoBridge"))
}

// The LINK step needs the .a present. The cinterop step only parses the
// def file's C declarations and never opens the binary, so it is
// deliberately not gated on the build — gating it would make Native
// cinterop tasks fail at configuration time on a non-Mac.
afterEvaluate {
    tasks.matching {
        it.name.startsWith("link") &&
            (
                it.name.contains("IosArm64") ||
                    it.name.contains("IosSimulatorArm64") ||
                    it.name.contains("IosX64")
                )
    }.configureEach {
        dependsOn(buildIosCryptoBridge)
    }
}
