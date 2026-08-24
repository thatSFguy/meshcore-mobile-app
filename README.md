# MeshCore Hardened (MCH)

*A hardened, self-contained [MeshCore](https://meshcore.co.uk/) client for Android — off-grid
encrypted messaging over LoRa, with no servers, no accounts, and no app-store lock-in.*

[![Latest release](https://img.shields.io/github/v/release/thatSFguy/meshcore-mobile-app?label=latest&sort=semver&color=blue)](https://github.com/thatSFguy/meshcore-mobile-app/releases/latest)
[![Android CI](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/android-ci.yml/badge.svg)](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/android-ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)

## Why you might want this

**Two outbound connections, and you trigger both.** OpenStreetMap tiles on the Map tab, and the
MeshCore firmware list and image from GitHub when you press the button. That is the entire list —
no analytics, no crash reporter, no Google Play Services, no Firebase, no account, no server of
mine anywhere. It is a promise you can check with a packet capture, which is why there is no
crash reporter: one call home, unasked, would end it. The app runs the same on a de-Googled ROM.

**Your secrets are sealed and your history is encrypted.** Login passwords, channel PSKs, community
secrets and the identity seed live in the Android Keystore (AES-GCM, key in the TEE/StrongBox); if
the Keystore is unavailable the app declines to store them rather than quietly falling back to
plaintext. The message database is SQLCipher-encrypted, and Auto Backup is off so none of it
reaches a cloud backup.

**It refuses to overstate what it knows.** Channels are labelled *obfuscated, not secure* on every
surface, because AES-ECB with a 2-byte MAC is what the protocol mandates. A route hop is named only
when exactly one contact matches its truncated hash, and stays `(N matches)` otherwise. A delivery
tick comes from a real end-to-end ACK and nothing else. "Last heard" means when your radio heard
the node, never what the node claims about itself. Where the app cannot know, it says so.

**Every advert is verified before it can become a contact**, so a forged advert cannot spoof an
identity or a GPS position, and channel sender names are never trusted for identity, contact
mutation or echo suppression — they are attacker-controllable display text.

**It is a full client, not a stripped one.** Direct messages and channels with real retry, a node
map, routing tools, repeater and room administration, and **firmware updates over Bluetooth done
in the app** — the transfer itself, board confirmed by name, checksum checked, rather than handing
you off to Nordic's DFU tool. Self-contained is not the same as small.

**Native, and yours to build.** Kotlin Multiplatform with a foreground service for a persistent
radio link and real system notifications — no Dart runtime, twelve mainstream Android
dependencies, no third-party SDKs. AGPL-3.0, built and signed by CI from a tagged commit, so what
you install matches what the release page advertises.

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

1. **Grant permissions** on MCH's first launch — Bluetooth (to reach the radio) and notifications.
2. **Connect your radio**: *Settings → Connection → Add / connect node* → pick your MeshCore radio
   from the BLE scan (or plug it in over USB-OTG). The app remembers it and reconnects
   automatically from then on.
3. **Message someone**: contacts appear on the **Nodes** tab as their adverts arrive; tap one →
   *Open conversation*. Nodes heard but not yet added show under the **New** tab — tap *Add*.
4. **Join a channel**: **Chats** tab → **+** → name it (a `#hashtag` name derives its own key), or
   scan a community QR.
5. **See the mesh**: the **Map** tab plots every node advertising GPS.

### Joining a local mesh by QR

A mesh only exists because every node agrees on four radio values, and typing them correctly
is a poor way to join one. **[Mesh settings QR generator →](https://thatsfguy.github.io/meshcore-mobile-app/settings-qr/)**
makes a code your area can print or post; scanning it in MCH shows the settings and asks
before applying anything.

The page runs entirely in your browser — nothing is uploaded, and it keeps working offline
once loaded. It starts on MeshCore's USA/Canada default and offers wide-bandwidth profiles
whose air characteristics match what Meshtastic calls *LongFast* and *MediumFast* (the
frequencies are MeshCore's; the two networks do not interoperate). Settings you build
yourself are remembered in that browser's local storage.

The code carries frequency, bandwidth, spreading factor, coding rate, path-hash width and an
optional flood region — and deliberately **not** transmit power or channel keys. Power is the
legal limit where the person scanning is standing, and a channel key would make the code a
secret rather than something safe to pin to a noticeboard. Format:
[`MESHCORE_PROTOCOL.md` §11](MESHCORE_PROTOCOL.md); source: [`docs/settings-qr/`](docs/settings-qr/).

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

**Messaging** — direct messages and channels, with **automatic retry** on MeshCore's documented
terms: three attempts, each waiting the radio's own airtime-derived ACK timeout, and the last one
clears a dead path and floods so your radio can learn a live route from the reply. That fallback
is on by default and can be turned off. Delivery ticks come exclusively from real end-to-end ACKs.
Paged scrollback, long-press for copy / quote / message details (SNR, attempts, ack hash),
mark-unread, per-channel mute.

**Notifications** — inbound direct and channel messages, with per-kind switches and per-thread
mute. A **reply** shows the reply first and the quoted message behind the expander, rather than
running the two together. A **reaction to your own message** notifies too — a thumbs-up is often
the whole reply — while reactions to other people's messages stay quiet. Opening a conversation
clears its notification; you should not have to tap a notification to dismiss something you have
already read.

**Reactions** — MeshCore has no reaction field, so every client that offers them invents a text
convention. This app reads both conventions on the air and sends the one this mesh mostly runs;
an unmatched reaction renders as "reacted to an earlier message" rather than as raw wire text.
Wire formats and the reason for the choice: [`MESHCORE_PROTOCOL.md` §14](MESHCORE_PROTOCOL.md).

**Channels** — 16-byte PSKs, `#hashtag` key derivation, private channels with generated keys, and
**community QR join** (the community secret is stored in the Keystore and its channels derived from
it). Channels are presented as *obfuscated, not secure* — see the security note below.

**Nodes** — Contacts / Repeaters / Rooms / Sensors tabs plus a **discovery inbox** of signature-verified
adverts you haven't added yet. Favourites, search, hash-colored avatars, rename, and **QR
share/import that interoperates with the mainstream MeshCore app** — codes are emitted in its
`meshcore://contact/add?…` form (and the older signed-advert form is still accepted on scan).
A scanned contact card is unsigned, so the app shows you the public key and asks before adding
it, rather than trusting the name in the code.

**Routing** — per-contact **Auto / Flood / Manual** routing, a hop-by-hop manual path editor, a
record of every path seen or used with success/failure quality labels, and a **path trace** showing
each hop with its SNR. A received message's details sheet shows **Arrived via** — the route it
actually travelled, in travel order, drawn on a map. A hop that can't be identified stays a gap
rather than being credited to a guess.

**Repeats — who is actually carrying your traffic.** Two views of the same evidence, because
there are two questions. On a **sent message**, a `↻ 2` badge says how many nodes were heard
re-broadcasting *that message*, and its details sheet names them. This pairs with the delivery
tick to say something neither can alone: `✗ (try 3) · ↻ 2` means the mesh moved your message and
nobody answered, which is a different problem from one that never left your radio.

**Who repeats me** (Nodes → ⋮) answers the standing version — what the mesh around you looks
like. Send a flood advert and watch which nodes send a copy back; every row is a copy of your own
**Ed25519-signed** advert, so nobody else can add a node to your list without your private key.
It distinguishes a node that *heard you* from one that *you heard* — only the second has a
measured SNR — and says plainly that it is a floor rather than a coverage map, since a node that
relayed you onward without a copy returning cannot appear at all. "Node", not "repeater": room
servers relay, and so do companions with client-repeat enabled.

**Map** — every node advertising GPS, with type-specific markers and always-visible name labels.
Filter by node type, export nodes as GPX, clear the tile cache. Tiles cache in app-private
storage, and they and firmware downloads are the only HTTP the app makes.

**Firmware updates over Bluetooth** — for nRF52 radios on companion firmware v1.15 or newer,
which is when MeshCore began exposing Nordic's DFU service. Settings → Firmware updates the
radio you are connected to; the repeater hub has the same tile for a node you can stand next
to, since `start ota` travels over the mesh but the image cannot. Builds are fetched from the
MeshCore releases on GitHub and checked against the checksum published with them, or opened
from storage if you would rather download them yourself. The board is confirmed by name before
anything is written — a DFU package cannot say which board it is for, because every nRF52 board
declares the same device type. ESP32 boards are told plainly that their path is USB or their own
WiFi hotspot, which a browser does better than this app would.

**Repeater / room administration** — a **hub** with one screen per tool. You sign in with a
password (sealed in the Keystore if you ask); the node decides what that unlocks and reports it,
and the hub shows what you got — `ADMIN` or `GUEST`. There is no access-level control to set,
because there is no such choice to make. Behind the hub: a decoded **Status** panel (battery,
uptime, queue, RSSI/SNR, airtime, packet and duplicate counts, channel utilisation) plus
Cayenne-LPP telemetry, **Regions**, **Identity**, a form-based **Settings** editor that fetches
live values over the CLI and saves only what you changed, a raw **Console**, and a **Command
help** catalogue filtered by node role and session role — a guest is never offered a command the
node would refuse.

**Device settings** — a hub of grouped pages covering the full companion-command surface:
connection, transports, identity, radio parameters, clock, mesh policies (advert location,
multi-acks, telemetry permissions, path-hash width, flood scope), auto-add policy, custom
variables, channels, blocked senders, appearance, notifications, privacy, backup, retention, and a
redaction-aware diagnostics log that is **off by default**. Each row reports its current value, so
which transports are on, what frequency the radio is using and whether map tiles are being fetched
are answered without opening anything.

## Security posture

The protocol spec ([`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)) is reverse-engineered, and §12
lists the client-side mistakes a security review of an existing MeshCore client turned up — a
review this project ran, and whose fixes it wrote and submitted back to that client rather than
merely noting. The list is here because it is the standing checklist for *this* app, not as a
score against anyone else's. Each item is enforced here in code, not just documented:

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

## How it compares

The official MeshCore Android app is treated as the **feature floor**: its surface was inventoried
from its own APK, and anything it does that MCH doesn't is counted as a gap unless there is a
reason on the record. MCH is a from-scratch native Kotlin client, not a fork — nothing is carried
over but the wire protocol.

**[`PARITY.md`](PARITY.md) is that comparison**, surface by surface, with dates and reasoning:
what is done, the handful of rows still open (§13), what is out of scope and why (§11), and the
places this app deliberately handles something differently rather than copying (§12).

The short version: the commercial layer, the crash reporter, the server-mediated map features and
the device-identity services are not here and never will be. RF coverage and line-of-sight
modelling are out by a **scope** decision — they need a terrain-elevation service, and this app's
whole promise is that it talks to two hosts you asked it to talk to. That is the real reason, and
it is not a claim that the feature cannot be built well: MeshCore Open queries a genuine elevation
API for exactly this, which is a better answer than a phone-sized approximation would be. What is
left open is listed honestly in PARITY rather than quietly dropped.

## Project scope — personal app, shared in the open

MCH is a **personal app, released in the open**. It does what I need an off-grid MeshCore client
to do, and it is deliberately **closed to new feature requests**. The goal is the opposite of feature
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

### What you give up

The official MeshCore Android app is the reference implementation: more complete, more widely used,
translated into 30+ languages, and where new MeshCore features land first. **MCH is English only**,
and for most people that is the biggest thing on this page. It is one person's app, closed to
feature requests, and its design goal is to stop growing.

If you want the fullest MeshCore client, theirs is the honest recommendation. Use this one if the
trade you want is the other one — fewer features, no telemetry, no billing, two outbound
connections you have to ask for, and secrets in the Keystore.

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

- **[`CHANGELOG.md`](CHANGELOG.md)** — what shipped in each release. The GitHub release notes are
  built from it, and the app carries the same text so it is readable with no network.
- **[`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)** — the companion + over-the-air wire spec
  (frames, command/response/push codes, advert signatures, channel crypto, PSK derivation).
  Reverse-engineered, and it says so: where behaviour matters, the
  [MeshCore firmware](https://github.com/meshcore-dev/MeshCore) is the authority and this file
  loses to it.
- **[`LESSONS.md`](LESSONS.md)** — a post-mortem of shipping something complete and unusable, and
  **[`REBUILD-PLAYBOOK.md`](REBUILD-PLAYBOOK.md)** — the rules that came out of it. Both are about
  rebuilding an app people already like without wrecking the part they like.
- **[`SCOPE.md`](SCOPE.md)** — the locked v1 feature set, and what's deliberately deferred or cut.
- **[`PARITY.md`](PARITY.md)** — surface-by-surface comparison against the mainstream MeshCore
  Android app, which is treated as the minimum feature bar: what's done, what's outstanding,
  what's out of scope, and where this app deliberately handles something differently.
- **[`REUSE.md`](REUSE.md)** — the file-by-file map of what was carried over from the sibling
  [reticulum-mobile-app](https://github.com/thatSFguy/reticulum-mobile-app), which is where the
  transports, foreground service and several screens come from.
- **[`SECURITY_REVIEW.md`](SECURITY_REVIEW.md)** — the 2026-07-31 full-surface security review:
  findings, fixes, accepted risks, and what was verified sound.
- **[`CLAUDE.md`](CLAUDE.md)** — orientation for a fresh contributor (or agent).

## iOS

**Android is the shipping platform. iOS is a pre-alpha skeleton — read this before sideloading it.**

Every push builds it: `shared` compiles and its tests run on Kotlin/Native, and CI publishes an
**unsigned IPA**. What the app cannot yet do matters more than that it installs:

| | iOS today | Notes |
|---|---|---|
| Advert Ed25519 verification | ✅ | CryptoKit bridge (`shared/iosCryptoBridge`), verified on CI against RFC 8032 vectors — keys derive, signatures verify, tampered and malformed input is rejected. |
| BLE (Nordic UART) to a radio | ◐ | `IosBleTransport` (CoreBluetooth) exists and compiles; **never run against a radio.** iOS supports BLE fully — it is *classic* Bluetooth that needs MFi, and MeshCore does not use it. Not yet wired to a scanner or the UI. |
| USB serial | ❌ | Not practical on iOS for these radios. |
| TCP transport | ✅ | Only useful with a networked base-station radio, and still plaintext + off by default. |
| Message persistence | ❌ | In-memory only — history is lost when the app exits. |
| Channels, map, repeater admin | ❌ | Screens exist as a shell; the logic is shared but unwired. |

So: installable, and useful for looking at the shell or building on it — **not** a working off-grid
client. If you want to actually message someone over LoRa today, use the Android build.

**Sideloading it** (unsigned IPA, re-signed locally with a free Apple ID), and building it on a
Mac: [`iosApp/README.md`](iosApp/README.md).

## Licence

[AGPL-3.0-only](LICENSE). Third-party components keep their own licences: osmdroid and ZXing
(Apache-2.0), SQLCipher (BSD-3-Clause), Bouncy Castle (MIT), AndroidX/Room/Compose (Apache-2.0).

## About the name

The app is **MeshCore Hardened**; **MCH** is shorthand, used above and in conversation. The full
name is what ships — the launcher, the releases and the app itself all say MeshCore Hardened — and
the word doing the work is *Hardened*, which is a claim about this build that the
[security posture](#security-posture) section qualifies. Worth knowing if you go looking: **MCH**
on its own is a crowded acronym (mostly a blood-test value), so searching for it will not find
this. Search for MeshCore Hardened.

**Not affiliated with the official MeshCore app.** This is an independent third-party client;
"Hardened" refers to this build's posture — small attack surface, keystore-sealed secrets,
encrypted local storage — not to any change in the MeshCore protocol's own guarantees.
