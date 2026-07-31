# MeshCore Mobile App — project context

A minimal, native **MeshCore** client (off-grid encrypted LoRa messaging), built in the
mold of the sibling `../reticulum-mobile-app`: no servers, no accounts, no Google Play
Services, no analytics, smallest attack surface possible. This file orients a fresh agent;
the detailed specs are the three docs below.

## Status

**v1 implemented on Android (2026-07-31); iOS is a Phase-1 skeleton.** Layout:
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
- **`SCOPE.md`** — the locked v1 feature set (pruned from MeshCore Open's inventory).
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

## Suggested next steps

1. **Hardware validation** — connect to a real MeshCore radio over BLE (then USB): confirm
   the handshake, framing, contact/channel sync, DM send/ack, RX-log channel decrypt.
   Expect quirks in exact response ordering / firmware-version gates; fix against the
   engine's fake-radio tests as ground truth for intent.
2. Notifications for inbound messages (service already has the channel; wire
   MessageRepository events → NotificationCompat).
3. iOS Phase 2 — `IosBleTransport` (CoreBluetooth port of the sibling's), CryptoKit
   Ed25519 bridge (copy `iosCryptoBridge` pattern), SQLDelight persistence.
4. Release plumbing (signing env vars are already read by `androidApp/build.gradle.kts`;
   tag scheme `android-vX.Y.Z` matches the sibling).

## Conventions

- KMP + Gradle. Build/test: `JAVA_HOME=/home/robw/android-tools/jdk ./gradlew
  :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`.
- Git identity for this repo: `thatSFguy` / `rob@woodhousellc.com`. Private repo
  `github.com/thatSFguy/meshcore-mobile-app` (remote `origin`, branch `main`).
- Never commit secrets/signing keys (see `.gitignore`).
- End commit messages with the Co-Authored-By / Claude-Session trailer used across these
  repos.
