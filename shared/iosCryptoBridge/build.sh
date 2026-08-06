#!/usr/bin/env bash
# Build MeshCoreCrypto.swift as a static library for each iOS
# Kotlin/Native target. macOS-only — needs the Xcode Swift toolchain.
# Gradle's buildIosCryptoBridge task runs this before the Kotlin/Native
# link step that consumes libMeshCoreCrypto.a via cinterop.
#
# Outputs (one per Kotlin/Native target name, matching the -L path each
# target's binary is given in shared/build.gradle.kts):
#   shared/build/iosCryptoBridge/iosArm64/libMeshCoreCrypto.a
#   shared/build/iosCryptoBridge/iosSimulatorArm64/libMeshCoreCrypto.a
#   shared/build/iosCryptoBridge/iosX64/libMeshCoreCrypto.a

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARED_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_BASE="${SHARED_DIR}/build/iosCryptoBridge"
SRC="${SCRIPT_DIR}/MeshCoreCrypto.swift"

build_one() {
    local kotlin_target="$1"
    local triple="$2"
    local sdk="$3"
    local out="${OUT_BASE}/${kotlin_target}"

    mkdir -p "$out"
    # -runtime-compatibility-version none disables Swift's autolink of
    # swiftCompatibility56 / swiftCompatibilityPacks. Those shims
    # back-port newer runtime features to older OSes, but they are only
    # delivered through Xcode's own lib paths — Kotlin/Native's
    # test-binary link step does not see them and fails with
    # "Undefined symbols __swift_FORCE_LOAD_$_swiftCompatibility56".
    # Carried over from the sibling repo, where this surfaced the moment
    # iosSimulatorArm64Test was added — which this repo's CI now runs
    # too, so the flag is load-bearing here from day one.
    xcrun -sdk "$sdk" swiftc \
        -emit-library -static \
        -target "$triple" \
        -runtime-compatibility-version none \
        -module-name MeshCoreCrypto \
        -emit-module -emit-module-path "${out}/MeshCoreCrypto.swiftmodule" \
        -o "${out}/libMeshCoreCrypto.a" \
        "${SRC}"
    echo "[iosCryptoBridge] built ${kotlin_target} → ${out}/libMeshCoreCrypto.a"
}

build_one iosArm64           arm64-apple-ios15.0            iphoneos
build_one iosSimulatorArm64  arm64-apple-ios15.0-simulator  iphonesimulator
build_one iosX64             x86_64-apple-ios15.0-simulator iphonesimulator
