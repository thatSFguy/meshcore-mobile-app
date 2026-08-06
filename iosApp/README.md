# MeshCore iOS app — Phase 1 (skeleton)

SwiftUI mirror of the Android app, following the same staged bring-up the
sibling `reticulum-mobile-app` used for its iOS port.

## Status

**Phase 1 — compiles-against-the-framework skeleton.** The tab shell,
store bridge, and screens exist; the Kotlin `Shared` framework provides
the protocol/engine/transport layer. Not yet wired on real hardware:

- **TCP transport**: functional in `shared` (`TcpSocket.ios.kt`, POSIX
  sockets) — the first transport to bring up on device, gated behind the
  same off-by-default stern-warning toggle as Android.
- **BLE (NUS)**: needs an `IosBleTransport` (CoreBluetooth) in
  `shared/src/iosMain` — port of the sibling's `IosBleTransport.kt` with
  MeshCore's frame-per-write semantics (no KISS).
- **Ed25519**: bridged to CryptoKit as of 2026-08-06.
  `shared/iosCryptoBridge/MeshCoreCrypto.swift` exposes sign/verify/
  pubkey over a C ABI, cinterop'd as `mch_*`; `IosCryptoProvider` calls
  it and still fails CLOSED on any error, because a throw out of
  `ed25519Verify` would escape the RX collector and one malformed advert
  would deafen the app. ⚠ **Not yet verified by a green CI run** — it
  cannot be compiled off macOS, so until the iOS CI workflow goes green
  this is written-but-unproven. Advert verification, and therefore
  contact import, is what it unblocks.
- **Persistence**: in-memory only; port the sibling's SQLDelight
  scaffolding for durable messages.

## Build (macOS only)

```
brew install xcodegen
./gradlew :shared:assembleSharedXCFramework   # repo root
cd iosApp && xcodegen generate
open iosApp.xcodeproj
```
