# MeshCore Hardened — iOS app (0.1.0)

SwiftUI over the Kotlin `Shared` framework. Phase 2 began 2026-11-17; every
decision taken during it is in [`../IOS_DECISIONS.md`](../IOS_DECISIONS.md).

## Status

Compiled only by CI (`.github/workflows/ios-ci.yml`); releases are
cut by tagging `ios-vX.Y.Z` (`.github/workflows/ios-release.yml`, notes
from [`CHANGELOG.md`](CHANGELOG.md)). 0.1.0 has been run on an iPhone
against real radios — messaging and a repeater's admin screens; see the
changelog for what has and hasn't been tried on hardware.

**Works in code:**
- BLE (CoreBluetooth NUS) and TCP (behind the stern plaintext warning),
  under the shared `ReconnectSupervisor`, with the last radio remembered
  and restored at launch.
- Messaging through shared `MessagingSession`: inbound DMs and channel
  posts, the 3-attempt DM retry ladder with flood fallback, honest
  delivery status, dedup of retries and echoes, reactions sent and shown,
  unread counts and a tab badge, local notifications.
- History persisted per radio identity as one JSON file under iOS Data
  Protection, excluded from backup, with user-chosen retention (judged by
  arrival time, not the sender's clock).
- Nodes list on the shared `NodeListModel` (tabs, search, sort, filters)
  with the shared reachability verdict; reset path, remove from radio,
  block, adverts.
- Channels: join hashtag, create/join private, paste or open a
  `meshcore://` link, share (behind the warning), leave; hide a channel
  sender by name.
- Links and QR: opened links and scanned codes (VisionKit) are previewed
  by the shared `LinkImport` and applied only on confirmation; "My
  contact" as a QR (CoreImage). No QR dependency.
- Repeater/room admin: sign-in gate, hub tiles from the shared
  `repeaterHubTiles`, Status, Console (redacted display). No password is
  stored.
- Settings (Phase 3): hub-and-spoke from the shared `settingsGroups()`,
  as on Android — connection, transports, Bluetooth PIN, identity (name,
  position, adverts), radio (parameters, TX power, regional presets,
  settings QR in and out, live signal), clock, mesh policies (telemetry,
  hop-hash width, flood scope, regions with discovery), auto-add, custom
  variables, channels, blocking, notifications, diagnostics, backup and
  restore (encrypted, interchangeable with Android), retention, about.
- Ed25519 via CryptoKit (`shared/iosCryptoBridge`), verified on CI against
  RFC 8032; CryptoKit is hedged, see CLAUDE.md.

- Routing (Phase 4): Auto / Flood / Manual with hops picked from
  relays, the reply route, path history scored by delivery, trace, and
  "Check if they can hear you". Message info shows Arrived via /
  Repeated by. The map has a self pin, type colours, labels, filters,
  fit-all and tap-to-open. (GPX export, in 0.1.0, has since been removed.)

- Repeater admin (Phase 5): remote settings (all sections, validated),
  regions (tree, default, add/remove/flood/home, save), command help,
  maintenance commands, the guest card (owner, flooded regions), access
  list, neighbours with probe, and neighbour links on the map.

- Conversations: swipe-to-reply with quotes, `@` mentions, Resend,
  "Message privately" (request / accept over a channel), "Who repeats
  me", and the stale-node sweep.

**Not ported yet:** nothing user-facing beyond the back burner. Simple
mode, per-channel regions, route maps and remote settings codes came in
Phase 5. **Back burner by decision:** firmware updates and repeater identity rekey.

**Writing Swift against `Shared`:** read the interop rules at the end of
`IOS_DECISIONS.md` first — nested Kotlin types are nested Swift types,
keyword-named members are renamed, and no default arguments cross over.
Each wrong guess costs a full CI run.

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
