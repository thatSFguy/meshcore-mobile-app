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
  would deafen the app. **Verified on CI 2026-08-06** against RFC 8032
  vectors (`Ed25519Conformance`, run on both platforms): public keys
  derive correctly, our signatures verify under the published keys, RFC
  signatures verify, and tampered/short/all-zero inputs are rejected.
  Note CryptoKit is *hedged*, so it does not reproduce the RFC's example
  signature bytes — see CLAUDE.md. Advert verification, and therefore
  contact import, is unblocked.
- **Persistence**: in-memory only; port the sibling's SQLDelight
  scaffolding for durable messages.

## Build (macOS only)

The app is developed on Linux, where nothing Apple compiles — CI on a macOS runner is the only thing that builds it.


```
brew install xcodegen
./gradlew :shared:assembleSharedXCFramework   # repo root
cd iosApp && xcodegen generate
open iosApp.xcodeproj
```

## Installing the unsigned IPA

Apple requires every app to be signed by someone. This project has no Apple Developer account, so
CI ships the IPA **unsigned** and you re-sign it locally with your own free Apple ID — the same
posture as the sibling [reticulum-mobile-app](https://github.com/thatSFguy/reticulum-mobile-app).

**Where to get it:** the newest green run of the
[iOS CI workflow](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/ios-ci.yml) —
open it and download the `meshcore-hardened-ios-unsigned` artifact. (GitHub requires you to be
signed in to download workflow artifacts.) There is no AltStore source and no IPA on the release
pages yet; the Android releases carry APK/AAB only.

### One-time setup — pick one

1. **Sideloadly** (simplest, no auto-renewal) — install [Sideloadly](https://sideloadly.io/) on a
   Mac or Windows PC, plug the iPhone in, drag the `.ipa` in, sign in with a free Apple ID, click
   Start. You re-run it weekly; see renewal below.
2. **AltStore** (auto-renewing, needs a Mac) — install [AltServer](https://altstore.io/) on a Mac
   the phone can reach over Wi-Fi after one USB pairing, then AltStore on the phone. It re-signs
   every 7 days on its own while AltServer is running.
3. **SideStore** (auto-renewing, no Mac) — [SideStore](https://sidestore.io/) renews on-device
   using a paired developer disk image; the sign-in is the same free Apple ID flow.

### First run only — trust the profile

On the phone open **Settings → General → VPN & Device Management → Developer App**, find your
Apple ID, and tap **Trust**. iOS will not launch a re-signed app until you do.

### Signature renewal

A free Apple ID signature lasts **7 days**. AltStore and SideStore renew automatically while their
helper is alive; Sideloadly does not, so you re-run it weekly. Past 7 days the app stops launching
with "Untrusted Developer" until it is re-signed. A paid Developer Program account ($99/yr) extends
this to a year — this project doesn't have one, and given the app's whole premise is working with
no internet and no app-store infrastructure, that is unlikely to change soon.
