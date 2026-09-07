# MeshCore Hardened (MCH) — private off-grid LoRa messaging for Android

*An independent, open-source [MeshCore](https://meshcore.co.uk/) client for Android. Text people
over LoRa radio with no cell service, no internet, no accounts and no servers — on a phone that
does not need Google Play Services.*

[![Latest release](https://img.shields.io/github/v/release/thatSFguy/meshcore-mobile-app?label=latest&sort=semver&color=blue)](https://github.com/thatSFguy/meshcore-mobile-app/releases/latest)
[![Android CI](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/android-ci.yml/badge.svg)](https://github.com/thatSFguy/meshcore-mobile-app/actions/workflows/android-ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)

Pair the app with a MeshCore LoRa radio — Heltec, RAK, LilyGo T-Echo, Seeed and friends — over
**Bluetooth, USB-C or TCP**, and you have an encrypted messenger that works in the backcountry,
in a blackout, on a ridge, or anywhere the towers stop. Direct messages, group channels, a node
map, repeater administration, and **firmware updates flashed straight from your phone**.

| Chats | Nodes | Map |
|---|---|---|
| ![MeshCore Hardened chats screen on Android](docs/screenshots/01-chats.png) | ![MeshCore node list with contacts, repeaters and rooms](docs/screenshots/02-nodes.png) | ![LoRa mesh node map with GPS positions](docs/screenshots/04-map.png) |

| Repeaters | Node detail | Settings |
|---|---|---|
| ![MeshCore repeater admin hub](docs/screenshots/03-repeaters.png) | ![Node detail sheet with position and hops](docs/screenshots/07-contact-sheet.png) | ![Settings screen](docs/screenshots/05-settings.png) |

## Why use MeshCore Hardened?

**Nothing phones home. Ever.** Two outbound connections exist and you trigger both: map tiles when
you open the Map tab, and the firmware image when you press the update button. No analytics, no
crash reporter, no Google Play Services, no Firebase, no account, no server of mine anywhere. It
is a promise you can check with a packet capture — which is exactly why there is no crash
reporter. One call home, unasked, would end it. Runs identically on GrapheneOS, CalyxOS,
LineageOS and any other de-Googled ROM.

**Your history is encrypted on the phone, not just on the air.** The message database is
SQLCipher-encrypted. Login passwords, channel keys, community secrets and your identity seed live
in the Android Keystore with the key in the TEE or StrongBox — and if the Keystore is unavailable
the app refuses to store them rather than quietly writing plaintext. Android Auto Backup is off,
so none of it is copied to anyone's cloud.

**Flash your radio's firmware from your phone, over Bluetooth.** Nordic DFU spoken natively in
the app: it picks the image for your board, confirms the board by name, checks the checksum and
paces the transfer. No cable, no desktop, no handoff to a separate DFU tool.

**See the route a message actually took.** *Arrived via* draws the real path a message travelled
across the mesh, hop by hop, on a map. Repeaters show their **neighbour links** as coloured lines
with the signal written on each one. A `↻` badge names which nodes were heard rebroadcasting the
message you sent — so "the mesh moved it and nobody answered" stops looking like "it never left
your radio".

**It says "I don't know" instead of guessing.** A route hop is named only when exactly one contact
matches its hash, and stays `(N matches)` otherwise. A delivery tick comes from a real end-to-end
ACK and nothing else. "Last heard" means when *your radio* heard the node, never what the node
claims. Channels are labelled *obfuscated, not secure* on every screen, because AES-ECB with a
2-byte MAC is what the protocol mandates and pretending otherwise would be a lie about your
privacy. Where the app cannot know something, it tells you.

**Forged adverts cannot become contacts.** Every advert's Ed25519 signature is verified before a
node is imported, mapped or even shown in the discovery inbox, so nobody can spoof an identity or
a GPS position into your contact list. Channel sender names are treated as what they are —
attacker-controllable display text — and never used for identity, contact changes or echo
suppression.

**It is a full client, not a cut-down one.** Direct messages and channels with real retry,
mentions, reactions, quoting and notifications; a tile-backed node map; per-contact routing with a
hop-by-hop path editor; repeater and room administration with a live settings editor and console;
QR sharing; backup and retention. Self-contained is not the same as feature-poor.

**Native Android, and yours to build.** Kotlin Multiplatform with a foreground service for a
persistent radio link and real system notifications — no Dart runtime, twelve mainstream Android
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

## Which LoRa radios does it work with?

**Any radio running MeshCore companion firmware**, over **Bluetooth LE** (Nordic UART),
**USB-C serial** (CDC-ACM / CP210x) or **TCP** to a networked base station. The app speaks the
MeshCore companion protocol, so hardware support is whatever the firmware supports — Heltec
(V3, T114, Mesh Solar), RAK WisBlock (4631, 3401, WisMesh Tag), LilyGo T-Echo and T-Echo Lite,
Seeed Xiao nRF52 and Wio Tracker L1, MeshTracker X1, and the rest of the MeshCore board list.

**Firmware updates over Bluetooth** are narrower: nRF52 boards on companion firmware v1.15 or
newer, which is where Nordic legacy DFU is available. ESP32 boards still flash over USB from a
desktop.

> MeshCore and **Meshtastic** are different protocols on the same kind of hardware and **do not
> interoperate** — a MeshCore node cannot message a Meshtastic node. Many boards can run either
> firmware, so a radio you already own may well work here after reflashing. This app is a
> MeshCore client only.

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

## Features at a glance

The short version; the full inventory is **[`FEATURES.md`](FEATURES.md)**.

- **Transports** — BLE, USB serial and TCP, each with its own toggle. TCP is off by default behind
  a stern warning, and flagged for as long as it is connected. Auto-reconnect and a foreground
  service keep the radio link up.
- **Messaging** — direct and channel messages, retry on the firmware's documented terms, delivery
  ticks only from real ACKs, `@[Name]` **mentions** with autocomplete and a highlight when you are
  tagged, reactions in both conventions on the air, and **Arrived via**: the route a message
  actually took, drawn on a map.
- **Channels** — 16-byte PSKs, `#hashtag` derivation, community QR join. Labelled *obfuscated, not
  secure*, because AES-ECB with a 2-byte MAC is what the protocol mandates.
- **Nodes** — Contacts / Repeaters / Rooms / Sensors plus a discovery inbox of signature-verified
  adverts, search and sorting, QR share and import, **Who repeats me**, and a stale-node sweep that
  never touches favourites.
- **Routing** — Auto / Flood / Manual per contact, a hop-by-hop path editor, path history with
  quality labels, and traces showing each hop's SNR. A hop is named only on a unique match.
- **Map** — every node advertising GPS, and for a repeater the **neighbour links** it reports,
  coloured and weighted by signal with the quality written on the line.
- **Repeater and room administration** — a hub with decoded status, telemetry, neighbours, access
  list, regions, identity, a live settings editor, a console, and role-filtered command help.
- **Firmware updates over Bluetooth** — Nordic legacy DFU spoken in-app, board confirmed by name,
  checksum checked.
- **Settings** — the full companion-command surface as grouped pages with live subtitles, plus
  retention, backup, blocking, and a redaction-aware diagnostics log that is off by default.

## Security posture

The protocol spec ([`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)) is reverse-engineered, and §12
lists the client-side mistakes that a security review of
**[MeshCore Open](https://github.com/zjs81/meshcore-open)** (Flutter) turned up — a review this
project ran, and whose fixes it wrote and submitted back to that project rather than merely
noting them.

**To be explicit, because the next section compares this app to the official MeshCore Android
app: none of those findings are about that app.** It is closed source, so it has never been
security-reviewed here and could not be; only its *feature* surface was inventoried, from its
APK, for [`PARITY.md`](PARITY.md). Naming the reviewed client matters more than the tidier
sentence did — "an existing MeshCore client", sitting one paragraph above a comparison with
somebody else's app, is an accusation pointed in the wrong direction.

The list is here because it is the standing checklist for *this* app, not as a score against
anyone else's. Each item is enforced here in code, not just documented:

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

## FAQ

**Does it need Google Play Services?** No. There is no Play Services dependency, no Firebase and
no Google account. It runs the same on GrapheneOS, CalyxOS, LineageOS and other de-Googled ROMs.

**Is it on Google Play or F-Droid?** Neither. Install the signed APK from
[Releases](https://github.com/thatSFguy/meshcore-mobile-app/releases/latest), or use
[Obtainium](#via-obtainium-recommended-for-ongoing-updates) to track releases automatically.

**Does it need internet or a mobile signal?** No. Messaging is entirely over your LoRa radio.
The only two things that ever reach the internet are map tiles and firmware downloads, both
only when you ask for them — and map tiles can be switched off, after which the map plots
markers on a blank canvas.

**Are my messages encrypted?** A direct message is encrypted to the recipient's identity key —
by the radio's firmware, not by this app, which never sees the plaintext go out. **Group channels
are a weaker thing entirely**: AES-ECB with a 2-byte MAC, which hides traffic from casual
listeners but is not a privacy guarantee, and the app labels it *obfuscated, not secure*
everywhere it appears. On the phone, message history is SQLCipher-encrypted and secrets are
sealed in the Android Keystore.

**Can it update my radio's firmware?** Yes, over Bluetooth, for nRF52 boards on companion
firmware v1.15+. The app picks the image for your board, confirms the board by name and verifies
the checksum before writing.

**Does it work with Meshtastic?** No — different protocol. See
[above](#which-lora-radios-does-it-work-with).

**Is it a fork of the official MeshCore app?** No. It is a from-scratch native Kotlin client that
shares nothing with it but the wire protocol.

**What does "Hardened" mean here?** This build's posture — a small network and dependency
surface, keystore-sealed secrets, encrypted local storage, verified adverts. It does **not** mean
the MeshCore protocol's own guarantees are changed; channels are still obfuscated rather than
secure, and the app says so.

**Is there an iOS version?** Not a usable one yet — see [iOS](#ios).

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

- **[`FEATURES.md`](FEATURES.md)** — the complete feature inventory, which this README only
  summarises.
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
