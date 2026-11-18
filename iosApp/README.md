# MeshCore Hardened — iOS app (Phase 2, in progress)

SwiftUI over the Kotlin `Shared` framework. Phase 2 began 2026-11-17; every
decision taken during it is in [`../IOS_DECISIONS.md`](../IOS_DECISIONS.md).

## Status

Compiled only by CI (`.github/workflows/ios-ci.yml`) and **never yet run on an
iPhone against a radio** — treat everything below as written and
compiled, not proven.

**Works in code:**
- BLE (CoreBluetooth NUS) and TCP (behind the stern plaintext warning),
  under the shared `ReconnectSupervisor`, with the last radio remembered
  and restored at launch.
- Messaging through shared `MessagingSession`: inbound DMs and channel
  posts, the 3-attempt DM retry ladder with flood fallback, honest
  delivery status, dedup of retries and echoes, reactions as chips,
  unread counts and a tab badge, local notifications.
- History persisted per radio identity as one JSON file under iOS Data
  Protection, excluded from backup.
- Nodes list on the shared `NodeListModel` (tabs, search, sort, filters)
  with the shared reachability verdict; reset path, remove from radio,
  adverts.
- Channels: join hashtag, create/join private, paste or open a
  `meshcore://` link, share (behind the warning), leave.
- Settings: connection, the radio's parameters (read-only), about.
- Ed25519 via CryptoKit (`shared/iosCryptoBridge`), verified on CI against
  RFC 8032; CryptoKit is hedged, see CLAUDE.md.

**Not ported yet** (Android has them): repeater/room admin, editing radio
settings, region scopes, block list, contact import from QR/links, QR
scanning, path history and routing sheet, heard-via, retention settings,
backup/restore, firmware updates, Simple mode.

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
