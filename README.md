# MeshCore Hardened

*A hardened, minimal [MeshCore](https://meshcore.co.uk/) client for Android — off-grid
encrypted messaging over LoRa, with no servers, no accounts, and no app-store lock-in.*

**Not affiliated with the official MeshCore app.** This is an independent third-party client;
"Hardened" refers to this build's posture (small attack surface, keystore-sealed secrets,
encrypted local storage) — not to any change in the MeshCore protocol's own guarantees.

Native Kotlin Multiplatform client for the MeshCore mesh, built in the mold of its sibling
[reticulum-mobile-app](https://github.com/thatSFguy/reticulum-mobile-app): a real native app —
foreground service for a persistent radio link, system notifications on incoming messages — with
the smallest attack surface I can keep secure and reason about.

**No external dependencies.** No accounts, no API keys, no central server, no analytics, no Google
Play Services, no Firebase. All crypto runs locally; secrets live in the Android Keystore;
persistence is Room (SQLite). The **only** outbound HTTP the app ever makes is OpenStreetMap tile
fetches on the Map tab — everything else is MeshCore frames over the transport you attach
(BLE / USB, or TCP if you deliberately turn it on).

[![Latest release](https://img.shields.io/github/v/release/thatSFguy/meshcore-mobile-app?label=latest&sort=semver&color=blue)](https://github.com/thatSFguy/meshcore-mobile-app/releases/latest)
[![Android CI](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/android-ci.yml/badge.svg)](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/android-ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)

## Project scope — personal app, shared in the open

This is a **personal app, released in the open**. It does what I need an off-grid MeshCore client to
do, and it is deliberately **closed to new feature requests**. The goal is the opposite of feature
growth: the smallest, most static attack surface I can keep secure and reason about. You are very
welcome to:

- **Use it** — install the signed APK, attach your own MeshCore radio over BLE or USB, and message
  people. No account, no server, no telemetry.
- **Fork it** — it's [AGPL-3.0](LICENSE). Build your own version with whatever features you want;
  that's what the licence is for.
- **Report security issues** — see **[SECURITY.md](SECURITY.md)**. Vulnerability reports are the
  one kind of report I actively want; please report privately rather than opening a public issue.
- **Report bugs** in the *existing* feature set — a focused bug report is welcome.

What I'm **not** taking: feature requests, "please add X" issues, or feature PRs — they'll be closed
unmerged. Not because they're bad ideas, but because every added surface works against the security
goal. Fork away instead. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full policy.

## Install

### Via Obtainium (recommended for ongoing updates)

<a href='obtainium://app/{"id":"io.github.thatsfguy.meshcore.native","url":"https://github.com/thatSFguy/meshcore-mobile-app","author":"thatSFguy","name":"MeshCore Hardened","preferredApkIndex":0,"additionalSettings":"{\"includePrereleases\":false,\"fallbackToOlderReleases\":true,\"filterReleaseTitlesByRegEx\":\"\",\"filterReleaseNotesByRegEx\":\"\",\"verifyLatestTag\":false,\"dontSortReleasesList\":false,\"useLatestAssetDateAsReleaseDate\":false,\"trackOnly\":false,\"versionExtractionRegEx\":\"\",\"matchGroupToUse\":\"\",\"versionDetection\":true,\"releaseDateAsVersion\":false,\"useVersionCodeAsOSVersion\":false,\"apkFilterRegEx\":\"MeshCoreHardened-Android-.*-release\\\\.apk\\\$\",\"invertAPKFilter\":false,\"autoApkFilterByArch\":true,\"appName\":\"MeshCore Hardened\",\"shizukuPretendToBeGooglePlay\":false,\"allowInsecure\":false,\"exemptFromBackgroundUpdates\":false,\"skipUpdateNotifications\":false,\"about\":\"\"}"}'>
  <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">
</a>

[Obtainium](https://obtainium.imranr.dev/) pulls APKs straight from GitHub releases and notifies you
of updates, without going through Google Play.

**One-tap setup:** tap the badge above on a device that already has Obtainium installed, and accept
the import sheet.

**Manual setup** (Obtainium → **Add App**):

- **App source URL:** `https://github.com/thatSFguy/meshcore-mobile-app`
- **Filter APKs by Regex** (optional): `MeshCoreHardened-Android-.*-release\.apk$` — explicit; Obtainium
  skips the `.aab` regardless.
- Leave *Include prereleases* off.

### Manual APK

Download `MeshCoreHardened-Android-<version>-release.apk` from the
[latest release](https://github.com/thatSFguy/meshcore-mobile-app/releases/latest) and install it.
Every release is built and signed by CI from a tagged commit; `versionName` / `versionCode` are
derived from the git tag, so what you install matches what the release page advertises.

Requires **Android 8.0 (API 26)** or newer.

## Quick start

1. **Grant permissions** on first launch — Bluetooth (to reach the radio) and notifications.
2. **Connect your radio**: *Settings → Connection → Add / connect node* → pick your MeshCore radio
   from the BLE scan (or plug it in over USB-OTG). The app remembers it and reconnects
   automatically from then on.
3. **Message someone**: contacts appear on the **Nodes** tab as their adverts arrive; tap one →
   *Open conversation*. Nodes heard but not yet added show under the **New** tab — tap *Add*.
4. **Join a channel**: **Chats** tab → **+** → name it (a `#hashtag` name derives its own key), or
   scan a community QR.
5. **See the mesh**: the **Map** tab plots every node advertising GPS.

| Chats | Nodes | Map |
|---|---|---|
| ![Chats](docs/screenshots/01-chats.png) | ![Nodes](docs/screenshots/02-nodes.png) | ![Map](docs/screenshots/04-map.png) |

| Repeaters | Node detail | Settings |
|---|---|---|
| ![Repeaters](docs/screenshots/03-repeaters.png) | ![Node detail](docs/screenshots/07-contact-sheet.png) | ![Settings](docs/screenshots/05-settings.png) |

## Features

**Transports** — BLE (Nordic UART Service) and USB serial (CDC-ACM / CP210x) by default, each with
its own enable toggle: a disabled transport is never started, so it never scans, connects, or feeds
bytes to a parser. **TCP is off by default** behind a one-time stern warning — the MeshCore TCP link
is unencrypted and unauthenticated, and the UI keeps flagging it while connected. Saved-node list,
automatic reconnect with backoff, foreground service so the link survives backgrounding.

**Messaging** — direct messages and channels, with **automatic retry**: each attempt waits the
radio's own airtime-derived ACK timeout, backs off, and only reports failure after the budget is
spent. Delivery ticks come exclusively from real end-to-end ACKs. Paged scrollback, long-press for
copy / quote / message details (SNR, attempts, ack hash), mark-unread, per-channel mute.

**Channels** — 16-byte PSKs, `#hashtag` key derivation, private channels with generated keys, and
**community QR join** (the community secret is stored in the Keystore and its channels derived from
it). Channels are presented as *obfuscated, not secure* — see the security note below.

**Nodes** — Contacts / Repeaters / Rooms tabs plus a **discovery inbox** of signature-verified
adverts you haven't added yet. Favourites, search, hash-colored avatars, rename, and **QR
share/import that interoperates with the mainstream MeshCore app** — codes are emitted in its
`meshcore://contact/add?…` form (and the older signed-advert form is still accepted on scan).
A scanned contact card is unsigned, so the app shows you the public key and asks before adding
it, rather than trusting the name in the code.

**Routing** — per-contact **Auto / Flood / Manual** routing, a hop-by-hop manual path editor, a
record of every path seen or used with success/failure quality labels, and a **path trace** showing
each hop with its SNR.

**Map** — every node advertising GPS, with type-specific markers and always-visible name labels.
Filter by node type, export nodes as GPX, clear the tile cache. Tiles are the only HTTP the app
makes; they cache in app-private storage.

**Repeater / room administration** — admin **or guest (read-only)** login with the password sealed
in the Keystore, a decoded **Status** panel (battery, uptime, queue, RSSI/SNR, airtime, packet and
duplicate counts, channel utilisation) plus Cayenne-LPP telemetry, a form-based **Settings** editor
that fetches live values over the CLI and saves only what you changed, and a raw **Console**.
Commands are filtered by node role and by session role — a guest is never offered a command the
node would refuse.

**Device settings** — collapsible sections covering the full companion-command surface: identity,
radio parameters, clock, mesh policies (advert location, multi-acks, telemetry permissions,
path-hash width, flood scope), auto-add policy, custom variables, channels, theme, and a
redaction-aware diagnostics log that is **off by default**.

## Security posture

The protocol spec ([`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)) was reverse-engineered from a
security review of the MeshCore Open client, and §12 lists the mistakes that review found. Each is
enforced here in code, not just documented:

- **Advert Ed25519 signatures are verified** before any node is imported, mapped, or shown in the
  discovery inbox — an unsigned or forged advert can't spoof an identity or a GPS position.
- **Channel sender names are never trusted** for identity, contact mutation, or echo suppression;
  they're attacker-controllable display text.
- **Secrets live in the Android Keystore** (AES-GCM, key in the TEE/StrongBox): login passwords
  (separate admin and guest slots), channel PSKs, community secrets. If the Keystore is unavailable
  the app declines to store them rather than falling back to plaintext.
- **Every frame parse is bounds-checked** — truncated or hostile frames degrade to "unknown frame"
  instead of crashing the RX path, and the test suite sweeps every response code at every short
  length.
- **Delivery is never inferred** from anything but a well-formed ACK.
- **Channel crypto is presented as obfuscation, not security** — the protocol mandates AES-ECB with
  a 2-byte MAC, which the app labels plainly in the channel UI rather than implying privacy it
  can't provide.
- The diagnostics log **redacts** `set prv.key`, passwords, and long hex blobs before a line is
  stored, and it is off by default.
- Auto Backup is disabled so message history and identity never reach a cloud backup.

## Build from source

```bash
git clone https://github.com/thatSFguy/meshcore-mobile-app
cd meshcore-mobile-app
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest   # tests
./gradlew :androidApp:assembleDebug                                  # APK
```

Requires JDK 17+ and the Android SDK (compileSdk 34).

## Project docs

- **[`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)** — the companion + over-the-air wire spec
  (frames, command/response/push codes, advert signatures, channel crypto, PSK derivation).
- **[`SCOPE.md`](SCOPE.md)** — the locked v1 feature set, and what's deliberately deferred or cut.
- **[`REUSE.md`](REUSE.md)** — the file-by-file map of what was carried over from
  reticulum-mobile-app.
- **[`SECURITY_REVIEW.md`](SECURITY_REVIEW.md)** — the 2026-07-31 full-surface security review:
  findings, fixes, accepted risks, and what was verified sound.
- **[`CLAUDE.md`](CLAUDE.md)** — orientation for a fresh contributor (or agent).

## Licence

[AGPL-3.0-only](LICENSE). Third-party components keep their own licences: osmdroid and ZXing
(Apache-2.0), SQLCipher (BSD-3-Clause), Bouncy Castle (MIT), AndroidX/Room/Compose (Apache-2.0).

## iOS

**Android is the shipping platform. iOS is a pre-alpha skeleton — read this before sideloading it.**

CI builds the iOS app on every push and publishes an **unsigned IPA**, so it can be installed today.
What it cannot yet do matters more than that it installs:

| | iOS today | Notes |
|---|---|---|
| BLE (Nordic UART) to a radio | ❌ | Needs a CoreBluetooth transport. **This is how you attach a radio** — without it the app cannot reach one over Bluetooth. |
| USB serial | ❌ | Not practical on iOS for these radios. |
| TCP transport | ✅ | Only useful with a networked base-station radio, and still plaintext + off by default. |
| Advert Ed25519 verification | ❌ | `IosCryptoProvider` throws pending a CryptoKit bridge, so **contact import stays disabled** rather than importing unverified. |
| Message persistence | ❌ | In-memory only — history is lost when the app exits. |
| Channels, map, repeater admin | ❌ | Screens exist as a shell; the logic is shared but unwired. |

So: installable, and useful for looking at the shell or building on it — **not** a working off-grid
client. If you want to actually message someone over LoRa today, use the Android build.

### Sideloading the unsigned IPA

Apple requires every app to be signed by someone. Since this project has no Apple Developer account,
CI ships the IPA **unsigned** and you re-sign it locally with your own free Apple ID. That is the
same posture the sibling [reticulum-mobile-app](https://github.com/thatSFguy/reticulum-mobile-app)
uses.

**Where to get it:** the `meshcore-hardened-ios-unsigned` artifact on any green
[iOS CI run](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/ios-ci.yml).
(Artifacts require being signed in to GitHub and expire after 7 days. Once iOS is worth releasing,
the IPA will move onto the release pages next to the APK.)

**One-time setup — pick one:**

1. **Sideloadly** (simplest, one-shot) — install [Sideloadly](https://sideloadly.io/) on a Mac or
   Windows PC, plug in the iPhone, drag the `.ipa` in, sign in with a free Apple ID, click Start.
2. **AltStore** (auto-renewing, needs a Mac) — install [AltServer](https://altstore.io/) on a Mac
   that the phone can reach over Wi-Fi after one USB pairing, then AltStore on the phone. It
   re-signs every 7 days on its own while AltServer is running.
3. **SideStore** (auto-renewing, no Mac) — [SideStore](https://sidestore.io/) renews on-device using
   a paired developer disk image; the sign-in is the same free Apple ID flow.

**Then, first run only:** on the phone open **Settings → General → VPN & Device Management →
Developer App**, find your Apple ID, and tap **Trust**. iOS will not launch a re-signed app until
you do.

**Signature renewal:** a free Apple ID signature lasts **7 days**. AltStore and SideStore renew
automatically while their helper is alive; Sideloadly does not, so you re-run it weekly. Past 7 days
the app stops launching with "Untrusted Developer" until it is re-signed. A paid Developer Program
account ($99/yr) extends this to a year — this project doesn't have one.

**Building it yourself** (macOS only — the app is developed on Linux, where nothing Apple compiles):

```bash
./gradlew :shared:assembleSharedXCFramework
brew install xcodegen && cd iosApp && xcodegen generate
open iosApp.xcodeproj
```

See [`iosApp/README.md`](iosApp/README.md) for the phase plan.
