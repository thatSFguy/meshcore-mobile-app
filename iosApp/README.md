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
- **Ed25519**: `IosCryptoProvider` implements SHA-2/HMAC/AES-ECB via
  CommonCrypto; Ed25519 throws pending the CryptoKit bridge (copy the
  sibling's `iosCryptoBridge` static-lib pattern). Until then advert
  verification — and therefore contact import — must stay disabled on
  iOS.
- **Persistence**: in-memory only; port the sibling's SQLDelight
  scaffolding for durable messages.

## Build (macOS only)

```
brew install xcodegen
./gradlew :shared:assembleSharedXCFramework   # repo root
cd iosApp && xcodegen generate
open iosApp.xcodeproj
```
