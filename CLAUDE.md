# MeshCore Hardened (repo: meshcore-mobile-app) — project context

A hardened, self-contained **MeshCore** client for off-grid encrypted LoRa messaging, built
in the mold of the sibling `../reticulum-mobile-app`: no servers, no accounts, no Google Play
Services, no analytics, no third-party SDKs. This file orients a fresh agent; the detailed
specs are the three docs below.

**"Minimal" was retired from the prose on 2026-08-20 — do not put it back.** It described the
SCOPE.md-era v1 and stopped being true when PARITY.md made the mainstream app the *feature
floor*: ~38k lines of production Kotlin, 40 screen files, a 20 MB APK, a tile-downloading map,
a QR scanner, a repeater CLI, and firmware flashing — which downloads a binary and writes it
into a device's flash, about the least minimal thing a mesh client can do. What is small, and
what the docs should say, is the surface *around* the features: twelve mainstream Android
dependencies, no SDKs, no telemetry, and exactly two outbound hosts. Same discipline the name
decision applied to *Hardened* — a word carrying a claim gets qualified or it gets dropped.

**Naming (2026-07-31, short form added 2026-08-06):** display name **MeshCore Hardened**,
short form **MCH** — introduced in the README header and usable in prose and conversation, but
NOT a replacement. The full name ships everywhere it is encountered cold: launcher, release
titles, artifact filenames, in-app About. Two reasons. *Hardened* is the word carrying a claim
the docs deliberately qualify, and an acronym hides it; and MCH on its own is a crowded acronym
(predominantly a haematology value), so it is useless for search. Launcher label **MC Hardened**
(`app_name` / `app_name_full` in strings.xml), APP_START identifies as `MeshCoreHardened`, release
artifacts are `MeshCoreHardened-Android-<ver>-release.{apk,aab}`. Renamed to avoid collision with
the official MeshCore app; "Hardened" describes this build's posture, NOT a protocol guarantee —
channels remain obfuscated-not-secure and the app must keep saying so.

## Status

**v1 shipped on Android (latest release `android-v0.2.4`); iOS builds but is a skeleton.**
Feature work is now driven by **[`PARITY.md`](PARITY.md)** — the mainstream MeshCore
Android app is the agreed floor for the feature set, with an explicit out-of-scope list
and the places we deliberately keep our own (stricter) handling. **Read PARITY.md before
picking up feature work**; it carries per-row status, dates, and the reasons behind
each deliberate difference. As of 2026-08-24 the tally is **45 ✅ · 9 ◐ · 8 ❌ · 3 ⛔ ·
1 ⚠** (`RepeaterNeighboursMapScreen` closed 2026-08-24 — the map draws a repeater's
neighbour links); blocks 1–4 are done, and so are the three §13 follow-ups (hop selection by
tapping, path-history cleanup, heard-via). What remains is listed with its reason in
PARITY §13 — a handful of rows blocked on hardware, on seeing the mainstream app run, or
on a translation programme. LOS/coverage modelling is **out of scope by decision** —
dedicated tools do it properly and the author maintains one.

**The recurring defect in this codebase is the hop-hash width.** It is a property of the
MESH (`DEVICE_INFO.pathHashByteWidth` — 2 on the author's), not a constant, and four
separate bugs have been one shape: a width-dependent value computed once as if the width
were 1, then carried around as truth (trace flags, path-history hop counts, Apply-path
token parsing, the repeater picker's `take(2)`). The fix that stuck was to stop carrying
derived values — `HopSelection` stores a node's full public key and derives the hash at
the current width on demand. Suspect this first when a route "looks right but does
nothing".

Layout:
- **`shared/`** — KMP. `protocol/` (Codes, guarded Buffers, Frames, ResponseParser,
  RawPacket, Advert w/ Ed25519 verify, ChannelCrypto, MeshIdentity), `engine/MeshCoreEngine`
  (handshake, serialized command queue, contact/channel sync, queue drain, RX-log decrypt,
  repeater admin), `transport/` (Transport iface, SerialFraming — **`<`/`>` + u16LE length,
  NOT COBS**; BLE = frame-per-write, no framing; `ReconnectSupervisor` — the
  build→connect→run→backoff loop, lifted out of MeshCoreService so it is testable
  against fake transports), `firmware/` (Nordic **legacy** DFU —
  `LegacyDfuSession` state machine, `DfuPackage`, `BootloaderPeer`, `FirmwareUpdater`,
  `FirmwareCatalog`/`BoardAssets`; see MESHCORE_PROTOCOL §11a, and note the bootloader
  advertises on the radio's MAC **+1** under a different name), androidMain (BLE/USB/TCP
  + BC crypto),
  iosMain (TCP + CommonCrypto; Ed25519 bridged to CryptoKit 2026-08-06 via
  `shared/iosCryptoBridge/` + cinterop — written but NOT yet proven by a green
  iOS CI run, which is the only thing that compiles it).
  Tests: `./gradlew :shared:testDebugUnitTest` — protocol vectors + fake-radio engine tests.
- **`androidApp/`** — Room DB (sealed PSKs), KeystoreSecretVault, MeshCoreService
  (foreground + reconnect supervisor), MeshCoreViewModel, Compose screens (chats,
  conversation, nodes+detail, osmdroid map, repeater admin, settings incl. stern TCP
  dialog + diagnostics). `./gradlew :androidApp:assembleDebug` (JDK at
  `/home/robw/android-tools/jdk`, SDK via `local.properties`).
  - **DRIVEN ON HARDWARE 2026-08-05** (Galaxy A42, 384dp, BLE to `MeshCore-Blue`,
    admin session on a live repeater). This is the first time the UI met a radio
    before shipping rather than after, and it found six defects in one session —
    all of them navigation or affordance, none of them caught by 540 tests. What it
    confirmed: `freqKhz` renders correctly as `910.525 MHz` against real hardware,
    and the live tile subtitles are the best thing in the rebuild. Screenshots +
    the `ui.sh` uiautomator helper make this repeatable; drive it weekly (§8.2).
  - **Neighbour links on the map were driven on hardware 2026-08-24** (SpartaMI,
    firmware v1.16.0, BLE to `MeshCore-BlueMobile`). A blank-password login was
    accepted, six neighbours came back, three had positions and were drawn. Two
    defects the tests could not see, both about reading: the colour legend under the
    map was grey-on-tiles and effectively invisible — deleted, the band is spelled
    out ON the line now ("Strong · 12.0 dB") — and the reading chips were drawn with
    their lines, which put them under the always-on node labels. A refusal also looks
    exactly like silence on this firmware, so the wording had to stop claiming one.
  - **Settings is hub-and-spoke** (2026-08-05, REBUILD-PLAYBOOK §6.2). `settings` is
    a grouped tile list (`SettingsScreen.kt`); each tile opens `settings/<route>`
    (`SettingsSpokeScreens.kt`), and the section bodies moved verbatim to
    `SettingsSections.kt`. It was eleven `ExpandableSection`s on one scroll, with
    `AppSection` holding another eight surfaces inside it. Tiles carry live
    subtitles — pure functions in `SettingsHubModel.kt`, tested, including that an
    unencrypted database wins its row and that TCP is always flagged. `ExpandableHint`
    (SettingsComponents) is the one-line-plus-"More" control for §6.3. Splitting
    can go too far: Appearance/Notifications/Privacy/Diagnostics were four screens
    holding one control each and are one `settings/app` screen again.
  - **The Nodes list is arranged by a pure model** (2026-08-20). Search, the sort menu
    (recent activity / last heard / name / fewest hops) and the filters (favourites,
    unread, heard in last 24 h) are all `NodeListModel.arrange` in
    `shared/presentation`, over a `NodeListItem` interface that `ContactEntity`
    implements — so the rules are pinned by tests instead of driven on a phone, and iOS
    inherits them. Rows state an age (`RelativeTime.ago`), not a date. Note what the
    anti-vacuity pass found: a `lastSeen > 0` key ahead of the descending sort *looked*
    like a guard against a hostile advert timestamp and was unreachable — plain
    descending already sinks 0 and negatives — and the test written to pin it passed
    with the key deleted. Deliberately regressing the model before trusting the suite is
    what caught it.
  - **Repeater admin is hub-and-spoke** (2026-08-05, REBUILD-PLAYBOOK §1.4a/§6.2).
    `repeater/{key}` is `RepeaterHubScreen` — identity card, the grant the node
    reported as an ADMIN/GUEST chip, and tiles into
    `repeater/{key}/{status,settings,regions,identity,console,help}`. Sign-in is
    `RepeaterLoginDialog`, which is the GATE — with no session there is no hub, only
    the dialog, and cancelling pops back. Tapping an infrastructure node in the Nodes
    list opens it directly; long-press opens the contact detail sheet. That path is
    3 taps to a repeater's status, matching the reference; the first cut was 5 and a
    scroll.
    This replaced `RepeaterAdminScreen`'s six scrollable tabs — the shape LESSONS
    §13 named. Which tiles appear is `repeaterHubTiles()`, a pure function in
    `RepeaterHubModel.kt`, so the gating is tested without a device.
    Driven against a live repeater 2026-08-05.
- **`iosApp/`** — XcodeGen `project.yml` + SwiftUI skeleton; see `iosApp/README.md`.
- **Partly validated against a real radio.** Navigation, connection, Settings, the
  repeater hub, login and Status were driven on hardware 2026-08-05 (see above).
  Still never run against a radio: backup/restore, retention, blocking, regional
  presets, sensors and identity-key management — and, as of 2026-08-13, **firmware
  updates over BLE**, which is the one that costs hardware if it is wrong. The
  protocol half is pinned against the bootloader's own source and tested against a
  fake bootloader; nothing has yet met a real one. Do the recoverable steps first on a
  spare node: jump to the bootloader and come back with opcode `6` (system reset)
  without flashing, then re-flash the version it is already running.

Reference docs:
- **`MESHCORE_PROTOCOL.md`** — the MeshCore companion + over-the-air wire spec (transports,
  command/response/push codes, frame layouts, advert Ed25519 signature, channel AES-ECB
  crypto, PSK derivation). Reverse-engineered during a security review of an existing
  client; it cites its evidence inline. Read this before touching protocol code, and note
  that it loses to the firmware — see *Where the protocol authority actually lives*.
  (§2 framing corrected 2026-07-31:
  USB/TCP use start-byte+length framing per the reference client, not COBS. §7
  `CMD_SEND_TRACE_PATH` expanded 2026-08-01 from live captures: the `flag` byte carries
  the hop-hash width and the payload is the route and is NOT optional — the one-line
  summary alone produces a trace no node answers.)
- **`PARITY.md`** — the live feature plan: surface-by-surface vs the mainstream app,
  what's done, what's out of scope and why, and where we deliberately differ. Supersedes
  SCOPE.md where they disagree.
- **`SCOPE.md`** — the original v1 feature set (pruned from an existing client's inventory).
- **`README.md`** — vision + non-goals; **`REUSE.md`** — what was copied from the sibling.

## The sibling repo this project draws from

- **`../reticulum-mobile-app`** — the **code foundation to reuse.** Native Kotlin
  Multiplatform Reticulum/LXMF client by the same author. Structure: `:shared` (commonMain
  = protocol + transports + DB + ViewModel, no UI), `androidApp` (Jetpack **Compose**
  screens under `.../android/ui/screens/`), `iosApp` (**SwiftUI**). **MeshCore uses the
  exact same transports** (BLE Nordic UART Service, TCP, USB-serial), so its transport
  layer, foreground service, reconnect supervisors, DB/VM scaffolding, and the reusable
  screens (Messages/Conversation/Nodes/Settings/DetailSheet/QrScanner/Theme) come across.
  **Drop** its Reticulum-specific parts (NomadNet/Micron browser, RRC Rooms, Graph, LXST
  voice/opus). Prefer **copy-the-pieces-into-this-repo** over fork-and-delete (forking drags
  in the whole Reticulum stack — the opposite of a lean dependency surface).

**There is no second sibling.** Early protocol work leaned on a third-party Flutter client
in a neighbouring directory. That is over: it is not consulted, not cited in prose, and not
somewhere to go for an answer. It is a *client*, which means it records one author's
decisions and nothing more — and it has already been wrong here in a way that reached this
codebase (see *Where the protocol authority actually lives*). Protocol questions go to the
firmware. Existing `// ported from …` comments in `shared/protocol/` stay as provenance for
byte layouts already derived; they are history, not a pointer.

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
- **Deferred:** telemetry, RF stats, neighbors, discovery-as-separate (all since done —
  see PARITY). **Cut:** web gate, on-device LLM translation, GIF picker, voice, and
  LOS/coverage modelling (2026-08-01: better tools exist and the author has a repo for
  it; a phone-sized terrain approximation would be confidently wrong exactly when it
  mattered).

## Security carry-over (do NOT repeat the §12 client-side findings — MESHCORE_PROTOCOL §12)

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
- **iOS cannot be compiled locally.** The app is developed on Linux with no Kotlin/Native
  Apple toolchain, so `.github/workflows/ios-ci.yml` on a macOS runner is the ONLY thing
  that compiles it. It was removed 2026-08-02 (private repo, macOS minutes bill at 10x)
  and **restored 2026-08-06** once the repo went public, where those runners are free.
  It now also runs `:shared:iosSimulatorArm64Test`, so commonMain *and* commonTest are
  compiled for Native on every push touching `shared/` or `iosApp/`.
  The failure that left it red was `toSortedMap()` — a JVM-only API — in **commonMain**;
  fixed 2026-08-06. `SharedIsPlatformNeutralTest` now greps for that whole class of
  mistake without needing a runner, but it is a lint: CI is the compiler. **Treat a red
  iOS run as a build error, not as an iOS problem** — it usually means JVM-only code
  reached `shared`.
- **The mainstream app's surface** was inventoried by pulling its APK and extracting Dart
  class names from `libapp.so` (it's Flutter). PARITY.md lists the results; re-derive with
  `adb pull` + a strings pass if more detail is needed.
## Suggested next steps

1. **Confirm heard-via against live traffic.** The code is done and tested (including a
   positive control asserting a real path reaches the event), and the "route unknown"
   fallback is verified on the phone — but no message carrying an actual route has been
   observed yet. The channel case is the one to watch: it is exact, so one inbound Public
   post should show named hops immediately. The direct-message case additionally depends
   on the firmware pushing `PUSH_CODE_LOG_RX_DATA` for TXT_MSG packets addressed to us,
   which has NOT been confirmed on this radio — if DM routes never appear, check that
   before suspecting the correlator.
2. **Validate the rest against the radio.** Backup/restore, retention, blocking, presets,
   sensors and identity-key management have still never run against hardware. (Regions
   and neighbours have, 2026-08-01.)
3. **`PARITY.md` §13 leftovers** — `HeardRepeatsScreen`, ACL write (blocked on a node that
   supports ACLs), and the rows that need the mainstream app running to specify.
4. Notifications for inbound messages (service already has the channel; wire
   MessageRepository events → NotificationCompat).
5. iOS Phase 2 — `IosBleTransport` (CoreBluetooth port of the sibling's), CryptoKit
   Ed25519 bridge (copy `iosCryptoBridge` pattern), SQLDelight persistence.
6. Release plumbing (signing env vars are already read by `androidApp/build.gradle.kts`;
   tag scheme `android-vX.Y.Z` matches the sibling).

## Testing — the standing expectation

**Everything fixed or added gets tests.** This is the user's explicit instruction, not a
nice-to-have, and it has repeatedly paid for itself:

- `JAVA_HOME=/home/robw/android-tools/jdk ./gradlew :shared:testDebugUnitTest
  :androidApp:testDebugUnitTest` — ~280 tests, seconds to run. Run it before claiming
  anything works.
- **A parser tested against captured frames proves nothing about the builder.** The
  trace feature shipped with nine tests, all on the RECEIVE side, and a sender that
  emitted a packet no radio would answer: it hardcoded the flags byte to 0 while our own
  parser read the hop-hash width back out of that same byte. Both halves of one codebase,
  disagreeing, with a green suite. When a spec line names a field without saying what
  goes in it (`[1] flag | payload?`), that is the moment to go read the reference
  client's *sender*, not just its parser.
  **This happened a second time on 2026-08-07** — `Fetch neighbours` sent one byte where
  the firmware reads eleven, and the suite's ninth test, `theRequestPayloadIsJustTheType`,
  *pinned the broken builder as correct*. A builder test written against our own parser is
  not a test; it is the same assumption twice. Write it against the firmware's **reader**
  and cite the file and line. Note the failure was silent and plausible: the node answered,
  the parse succeeded, and the UI invented "the table may be paged" to explain it — so a
  feature that has never once returned data can look merely flaky for months.
- **Pin the real captured value, not just a property.** The `path_len` bug ("34 hops"
  for a 4-hop node) is pinned with the actual `0x44` byte from a live contact. The
  reference client's own reaction tests assert only that its hash is deterministic and
  four hex digits — which is exactly why there were no ground-truth vectors to check our
  Dart-hash reimplementation against. Don't repeat that mistake here.
- **Negative and hostile cases carry the weight.** Anything parsed off the mesh or out of
  a QR is attacker-controlled: test truncation, wrong lengths, non-hex, duplicate
  parameters, all-zero keys, oversized input. Several of these found real defects.
- **A suite of "asserts null" needs a positive control.** Most of the heard-via tests
  assert that a route is NOT claimed — ambiguous packets, wrong sender, stale timing.
  Every one of them would pass if the feature did nothing at all. The test that carries
  the suite is the one asserting the real path `b389c985` reaches the event. Whenever
  correctness means *declining* to answer, pin the case where it must answer.
- **Exhaustive sweeps find what examples miss.** Round-tripping the whole `path_len`
  space surfaced that 63 hops at 4-byte hashes encodes to `0xFF` — the flood sentinel —
  so a pinned route would have silently become "no route".
- **Let testability drive design.** `ReactionCounts` moved from androidApp to `shared` and
  dropped `org.json` because that class isn't available in local unit tests; the codec is
  now testable off-device and reusable on iOS.
- **Protocol logic belongs in `shared`**, where it can be tested without a device or an
  emulator. UI helpers that carry real logic (hop labels, quote splitting, drift
  formatting) should be pure functions so `androidApp/src/test` can reach them.

## Where the protocol authority actually lives

> **Rule: check the original source, never a third-party app.** A client tells you what
> *that client's author decided*. It is evidence about one implementation, never a spec —
> and when it disagrees with the firmware, the firmware is what your radio is running.

**Order of authority**, highest first:

1. **Firmware source and docs — `github.com/meshcore-dev/MeshCore`** (`src/`, `docs/faq.md`,
   merged PRs). This is what the hardware actually does.
2. **`MESHCORE_PROTOCOL.md`** here, which cites its evidence — but is reverse-engineered,
   so it loses to (1).
3. **A client** — any of them. Useful for *how* something is presented; not authoritative
   on *what* the protocol or the defaults are.

`liamcottle/meshcore.js` is a good cross-check and worth consulting, but it is a protocol
layer only (advert/packet/buffers/constants) with no retry or routing policy — which is
itself an answer: policy belongs to the app, not the wire.

Always separate **merged** from **proposed**. PR #2594 (6-byte ACKs) is merged and shipped;
issues #1342 / #1397 / #1489 are open proposals and must not be built against.

**Earned again on 2026-08-23, on a size rather than a policy.** The repeater rekey screen
sent a 32-byte Ed25519 seed to `set prv.key`, which reads `PRV_KEY_SIZE` = **64** bytes and
refuses anything else on length alone — so every key the app ever generated came back
"Error, bad key", and every key it read back (also 64 bytes) failed its own 64-hex-character
validator and was reported as a refusal. Both halves had tests; both tests asserted the
app's own idea of the length. MESHCORE_PROTOCOL §12 had said "expanded 64-byte private key"
since it was written. Same shape as the trace flags and the neighbours request: a
width-or-size read from our own code instead of the firmware's reader. See
MESHCORE_PROTOCOL §12 for the `prv.key` wire form with citations, and note the other half of
that fix — a node's on-air name is a *prefix* of its public key, so generating an identity
means checking the leading bytes against the mesh, not just making 32 random ones.

**This rule was earned on 2026-08-05.** A retry recommendation derived from a third-party
client alone got the attempt count wrong (that client uses 5; the documented default is 3),
got the default wrong (it ships the flood fallback *disabled*; the documented default is on)
and missed the path reset entirely — the one part that makes the feature work. MeshCore's own
FAQ ([`meshcore-dev/MeshCore/docs/faq.md`](https://github.com/meshcore-dev/MeshCore/blob/main/docs/faq.md),
mirrored at docs.meshcore.io/faq) documents DM retry as **3 attempts, flood on the last,
resetting the path, on by default, toggleable**. That is what ships here. The point is not
that the client was badly written — it is that a client is one author's answer, and three of
its four details differed from the firmware's.

## Settled decisions — do not re-litigate without new evidence

**CryptoKit's Ed25519 is hedged; Bouncy Castle's is RFC-deterministic.** Proven on CI
2026-08-06, not assumed. RFC 8032 derives the signing nonce from key and message alone, so a
conforming implementation reproduces the spec's example signatures byte for byte — Bouncy
Castle does, and `Ed25519Conformance.signaturesAreRfcDeterministic` asserts it on Android.
Apple's `Curve25519.Signing` mixes in randomness and emits a **different valid signature every
time**. Both are correct Ed25519. Do NOT "fix" the iOS bridge to match the RFC bytes, and do not
assert byte equality in a cross-platform test: the property that matters is that a signature
*verifies*, which is all the mesh ever checks, and which
`Ed25519Conformance.signaturesVerifyUnderThePublishedKey` asserts on both platforms.
The diagnostic pattern, if this resurfaces: correct public key + verify working + signature
bytes differing means hedging, not a broken bridge.


**There is no reason to sign in as a guest when you hold the admin password.** Verified
2026-08-05 against the firmware: `set guest.password` is a *repeater setting* under "allow
read-only guest access", `CMD_SEND_LOGIN` carries a password and no requested-level field,
and the ACL (0 Guest / 1 Read-only / 2 Read-write / 3 Admin) is keyed by pubkey. A guest
credential is something an operator hands to **other people**. `GUEST` in our UI is what
someone else sees on your repeater, or what you see on theirs. So: never add a role picker,
and never build a "switch to guest" affordance. The real scenario behind that screen is a
wrong password — error recovery, which the dialog already does.

**Don't invent the user.** The guest→admin "re-authentication flow" was proposed here on the
strength of it sounding plausible, for a person who does not exist. Before building an
affordance, name who needs it and what they were doing — if that story needs inventing, the
affordance does too.

**Splitting a screen can go too far.** Four Settings spokes each held one control;
"Privacy and network" was a single toggle against 70% empty screen. §6.2 says screens are
cheap, not free: a spoke holding less than the tile that leads to it is worse than the
section it replaced.

**Measure tap counts, don't assume them.** REBUILD-PLAYBOOK §4 gate 2 asks for ±1 of the
reference. The first hub-and-spoke cut matched the reference's *structure* and was +2 taps
and a scroll on the commonest admin task, which went unnoticed because nobody counted.
Counting is: drive it, and count.

## Screenshots and demo assets — redaction is mandatory

`.gitignore` already says it: `docs/screenshots/raw/` holds unredacted originals and is
**never committed**. Only boxed output goes in `docs/`. This was written down in July, and
broken on 2026-08-05 by committing raw captures straight into `docs/screenshots/` and into
`docs/demo.gif` — which published a third party's first name beside their amateur callsign
(the FCC licence database turns that into a legal name and mailing address), other people's
repeater positions to five decimal places, and a "Distance away" reading that trilaterates
the phone that took the shot.

Use `tools/redact-screenshot.py raw.png out.png` — it takes boxes from the live uiautomator
tree rather than from eyeballing pixels, and covers node public keys, coordinates, amateur
callsigns and distance-away. Two things it cannot see, so handle them by hand:
- **Map labels** are canvas-drawn, not in the accessibility tree. Turn off Map → ⋮ → *Show
  labels* before capturing.
- **Content behind a modal sheet** is absent from the tree; pass `box=x,y,w,h` for it.

Region-level location is fine (the mesh is around Grand Rapids and that is not a secret).
Points are not.

**Capture verified stills, not a blind screen recording.** Two `adb shell screenrecord` runs
driven by scripted taps both went wrong invisibly — one hit the wrong screen because a
session was still live, the other sat on Settings for 50 seconds — and neither was detectable
until the frames were sampled afterwards. Capturing step by step and dumping the UI tree
after each tap means every frame is confirmed before it ships. `docs/demo.gif` is built that
way; scrolls do not animate, and that is the right trade.

## Conventions

- **Release notes come from `CHANGELOG.md`.** The release workflow extracts the `## <version>`
  section for the tag and fails the build if there isn't one, so a tag without notes cannot
  ship. The app carries the same text in `AboutSection.kt` (offline-readable), and
  `ChangelogTest` holds the two to the same version list — they had already drifted once,
  with 0.5.2 shipping a fix the in-app list never mentioned. Write the section **before**
  tagging.

- KMP + Gradle. Build/test: `JAVA_HOME=/home/robw/android-tools/jdk ./gradlew
  :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`.
- Git identity for this repo: `thatSFguy` / `rob@woodhousellc.com`. Private repo
  `github.com/thatSFguy/meshcore-mobile-app` (remote `origin`, branch `main`).
- Never commit secrets/signing keys (see `.gitignore`).
- End commit messages with the Co-Authored-By / Claude-Session trailer used across these
  repos.
