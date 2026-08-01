# MeshCore Hardened (repo: meshcore-mobile-app) — project context

A hardened, minimal **MeshCore** client for off-grid encrypted LoRa messaging, built in the
mold of the sibling `../reticulum-mobile-app`: no servers, no accounts, no Google Play
Services, no analytics, smallest attack surface possible. This file orients a fresh agent;
the detailed specs are the three docs below.

**Naming (2026-07-31):** display name **MeshCore Hardened**, launcher label **MC Hardened**
(`app_name` / `app_name_full` in strings.xml), APP_START identifies as `MeshCoreHardened`, release
artifacts are `MeshCoreHardened-Android-<ver>-release.{apk,aab}`. Renamed to avoid collision with
the official MeshCore app; "Hardened" describes this build's posture, NOT a protocol guarantee —
channels remain obfuscated-not-secure and the app must keep saying so.

## Status

**v1 shipped on Android (latest release `android-v0.2.4`); iOS builds but is a skeleton.**
Feature work is now driven by **[`PARITY.md`](PARITY.md)** — the mainstream MeshCore
Android app is the agreed floor for the feature set, with an explicit out-of-scope list
and the places we deliberately keep our own (stricter) handling. **Read PARITY.md before
picking up feature work**; it carries per-row status, dates, and one blocked item.
Blocks 1 and most of 2 are done; **regions (§8) is the next unit of work.**

Layout:
- **`shared/`** — KMP. `protocol/` (Codes, guarded Buffers, Frames, ResponseParser,
  RawPacket, Advert w/ Ed25519 verify, ChannelCrypto, MeshIdentity), `engine/MeshCoreEngine`
  (handshake, serialized command queue, contact/channel sync, queue drain, RX-log decrypt,
  repeater admin), `transport/` (Transport iface, SerialFraming — **`<`/`>` + u16LE length,
  NOT COBS**; BLE = frame-per-write, no framing), androidMain (BLE/USB/TCP + BC crypto),
  iosMain (TCP + CommonCrypto; Ed25519 stubs pending the CryptoKit bridge).
  Tests: `./gradlew :shared:testDebugUnitTest` — protocol vectors + fake-radio engine tests.
- **`androidApp/`** — Room DB (sealed PSKs), KeystoreSecretVault, MeshCoreService
  (foreground + reconnect supervisor), MeshCoreViewModel, Compose screens (chats,
  conversation, nodes+detail, osmdroid map, repeater admin, settings incl. stern TCP
  dialog + diagnostics). `./gradlew :androidApp:assembleDebug` (JDK at
  `/home/robw/android-tools/jdk`, SDK via `local.properties`).
- **`iosApp/`** — XcodeGen `project.yml` + SwiftUI skeleton; see `iosApp/README.md`.
- **NOT yet validated against a real radio** — that's the next milestone.

Reference docs:
- **`MESHCORE_PROTOCOL.md`** — the MeshCore companion + over-the-air wire spec (transports,
  command/response/push codes, frame layouts, advert Ed25519 signature, channel AES-ECB
  crypto, PSK derivation). Reverse-engineered from `../meshcore-open` during a security
  review. Read this before touching protocol code. (§2 framing corrected 2026-07-31:
  USB/TCP use start-byte+length framing per the reference client, not COBS.)
- **`PARITY.md`** — the live feature plan: surface-by-surface vs the mainstream app,
  what's done, what's out of scope and why, and where we deliberately differ. Supersedes
  SCOPE.md where they disagree.
- **`SCOPE.md`** — the original v1 feature set (pruned from MeshCore Open's inventory).
- **`README.md`** — vision + non-goals; **`REUSE.md`** — what was copied from the sibling.

## The two sibling repos this project draws from

- **`../reticulum-mobile-app`** — the **code foundation to reuse.** Native Kotlin
  Multiplatform Reticulum/LXMF client by the same author. Structure: `:shared` (commonMain
  = protocol + transports + DB + ViewModel, no UI), `androidApp` (Jetpack **Compose**
  screens under `.../android/ui/screens/`), `iosApp` (**SwiftUI**). **MeshCore uses the
  exact same transports** (BLE Nordic UART Service, TCP, USB-serial), so its transport
  layer, foreground service, reconnect supervisors, DB/VM scaffolding, and the reusable
  screens (Messages/Conversation/Nodes/Settings/DetailSheet/QrScanner/Theme) come across.
  **Drop** its Reticulum-specific parts (NomadNet/Micron browser, RRC Rooms, Graph, LXST
  voice/opus). Prefer **copy-the-pieces-into-this-repo** over fork-and-delete (forking drags
  in the whole Reticulum stack — the opposite of minimal).
- **`../meshcore-open`** — the **Flutter reference client** the protocol spec was derived
  from (different stack — Dart — so it's a *reference*, not code to copy). A security-audited
  private fork lives at `github.com/thatSFguy/meshcore-open-secure` with `SECURITY_AUDIT.md`
  and ~10 fix PRs; consult it for protocol details and for the concrete pitfalls to avoid.

## Architecture plan

Kotlin Multiplatform, mirroring reticulum-mobile-app:
- `:shared` `commonMain` — protocol + transports + DB + ViewModel logic.
  - `protocol/` (new, from `MESHCORE_PROTOCOL.md`): `Codes` (command/response/push enums),
    `Frames` (command builders + response/push parsers), `Advert` (parse + Ed25519 verify),
    `ChannelCrypto` (PSK derivation + AES-ECB/2-byte-MAC decrypt), `Identity`
    (expanded-key Ed25519).
  - `transport/` — copied from reticulum-mobile-app (BLE NUS / USB serial / TCP, COBS,
    reconnect, foreground service).
- `androidApp` — Compose screens (adapt reticulum-app's).
- `iosApp` — SwiftUI (adapt reticulum-app's). Android-first then port is a reasonable path.

## v1 scope (see SCOPE.md for the full list)

- **Transports:** BLE + USB by default. **TCP is OFF by default, behind a feature toggle**
  with a one-time *stern unencrypted/unauthenticated warning*; per-transport enable toggles
  like the reticulum app. (User does not use WiFi; TCP is only for a networked base-station
  radio.)
- **Messaging:** direct messages + **channels** (16-byte PSK, channel management, community
  QR join).
- **Map:** in-app node map (the one feature that makes outbound HTTP for tiles).
- **Repeater/room admin:** login + CLI + settings editor (highest-surface piece; keystore
  the login password, redact `set prv.key` from logs).
- **Settings:** device (radio params/identity/name/GPS) + app; one redaction-aware,
  off-by-default diagnostics log.
- **Deferred:** telemetry, RF stats, neighbors, discovery-as-separate, LOS/path-trace map
  overlays. **Cut:** web gate, on-device LLM translation, GIF picker, voice.

## Security carry-over (do NOT repeat MeshCore Open's findings — MESHCORE_PROTOCOL §12)

Verify advert Ed25519 signatures before importing a contact; never trust channel sender
names (identity/mutation/echo-suppression); store all secrets (login passwords, channel
PSKs, community secrets, identity key) in the platform keystore/keychain, never plaintext;
guard every frame parse against short/malformed input; warn that TCP is plaintext; present
channels as obfuscated (AES-ECB + 2-byte MAC), not secure.

## Working on this repo — practical notes

- **Installing to the phone.** Plain `adb install` dies mid-transfer on this WSL2 +
  usbipd setup (the USB tunnel drops on a ~25 MB sustained write). Use
  `adb install -r -d --no-streaming <apk>` — push-then-`pm install`, which has been
  reliable. The `-d` matters too: local builds get `versionCode 1` unless HEAD sits
  exactly on an `android-v*` tag, so installing over a tag-built APK looks like a
  downgrade. Never uninstall to get around it — that takes the message database with it.
- **iOS has no local compiler.** The app is developed on Linux; `.github/workflows/ios-ci.yml`
  on a macOS runner is the only thing that compiles it. Treat a red run there as a build
  error, and expect a ~7 minute round-trip per attempt.
- **The mainstream app's surface** was inventoried by pulling its APK and extracting Dart
  class names from `libapp.so` (it's Flutter). PARITY.md lists the results; re-derive with
  `adb pull` + a strings pass if more detail is needed.
## Suggested next steps

1. **Work `PARITY.md` §8 (regions)** — add/manage/discover regions and the repeater's
   default region scope. We expose flood scope as a single setting today. No security
   landmines in this one, just four screens' worth of work.
2. Notifications for inbound messages (service already has the channel; wire
   MessageRepository events → NotificationCompat).
3. iOS Phase 2 — `IosBleTransport` (CoreBluetooth port of the sibling's), CryptoKit
   Ed25519 bridge (copy `iosCryptoBridge` pattern), SQLDelight persistence.
4. Release plumbing (signing env vars are already read by `androidApp/build.gradle.kts`;
   tag scheme `android-vX.Y.Z` matches the sibling).

## Testing — the standing expectation

**Everything fixed or added gets tests.** This is the user's explicit instruction, not a
nice-to-have, and it has repeatedly paid for itself:

- `JAVA_HOME=/home/robw/android-tools/jdk ./gradlew :shared:testDebugUnitTest
  :androidApp:testDebugUnitTest` — ~280 tests, seconds to run. Run it before claiming
  anything works.
- **Pin the real captured value, not just a property.** The `path_len` bug ("34 hops"
  for a 4-hop node) is pinned with the actual `0x44` byte from a live contact. The
  reference client's own reaction tests assert only that its hash is deterministic and
  four hex digits — which is exactly why there were no ground-truth vectors to check our
  Dart-hash reimplementation against. Don't repeat that mistake here.
- **Negative and hostile cases carry the weight.** Anything parsed off the mesh or out of
  a QR is attacker-controlled: test truncation, wrong lengths, non-hex, duplicate
  parameters, all-zero keys, oversized input. Several of these found real defects.
- **Exhaustive sweeps find what examples miss.** Round-tripping the whole `path_len`
  space surfaced that 63 hops at 4-byte hashes encodes to `0xFF` — the flood sentinel —
  so a pinned route would have silently become "no route".
- **Let testability drive design.** `ReactionCounts` moved from androidApp to `shared` and
  dropped `org.json` because that class isn't available in local unit tests; the codec is
  now testable off-device and reusable on iOS.
- **Protocol logic belongs in `shared`**, where it can be tested without a device or an
  emulator. UI helpers that carry real logic (hop labels, quote splitting, drift
  formatting) should be pure functions so `androidApp/src/test` can reach them.

## Conventions

- KMP + Gradle. Build/test: `JAVA_HOME=/home/robw/android-tools/jdk ./gradlew
  :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`.
- Git identity for this repo: `thatSFguy` / `rob@woodhousellc.com`. Private repo
  `github.com/thatSFguy/meshcore-mobile-app` (remote `origin`, branch `main`).
- Never commit secrets/signing keys (see `.gitignore`).
- End commit messages with the Co-Authored-By / Claude-Session trailer used across these
  repos.
