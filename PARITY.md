# Feature parity vs the mainstream MeshCore Android app

**Policy (2026-08-01):** the mainstream app — `com.liamcottle.meshcore.android`,
"MeshCore", v1.47.0 — is the **floor** for this app's feature set. Anything it does that
we don't is a gap to close, unless it's explicitly out of scope below. This supersedes
the original pruned-from-an-inventory scoping in [`SCOPE.md`](SCOPE.md) for anything
the two disagree on.

**Two things parity does NOT mean:**

1. **Security posture is not negotiable.** Where their app trusts something we don't —
   a 4-byte hop hash as an identity, an unsigned contact card imported without a prompt —
   we ship the *feature* and keep *our* handling. Those rows are marked ⚠ and say what
   differs.
2. **No servers, no accounts, no analytics.** Features that require their backend, an
   account, or a payment are out of scope by construction, not by preference.

## How this was derived

Their app is Flutter; the UI is compiled into `libapp.so`. Pulled from the device
(`base.apk` + `split_config.arm64_v8a.apk`) and inventoried by extracting the Dart class
names — 97 `*Screen` / `*Sheet` / `*Dialog` classes plus the service layer. That's a
complete map of their **surface**; it does not describe their **behaviour**, so where a
row below says "partial" it's judged on what the screen must be doing, and a few will
need a look at the running app to settle.

Status key: ✅ have · ◐ partial · ❌ missing · ⛔ out of scope

---

## 1. Connection & setup

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `ConnectScreen`, `BluetoothSettingsScreen` | Settings → Connection, Transports | ✅ | We also gate each transport with an enable toggle; TCP is off by default behind a warning. |
| `SetupScreen` (first-run onboarding) | First-run setup checklist | ✅ | Closed 2026-08-01. A checklist, not a wizard — plenty of users arrive with a radio someone else configured and can skip straight past. Names the four params that must match, and that a wrong match looks like an empty app rather than an error. |
| `SuggestedRadioSettingsSelectorBottomSheet` (radio presets) | Settings → Radio → regional presets | ✅ | Closed 2026-08-01. All 47 presets transcribed from the reference client, grouped and searchable; the live radio's matching preset(s) are named. ⚠ Carries a regulatory caveat: these are what communities run, not legal advice. |
| `FactoryResetScreen` | Repeater admin → `erase` (confirmed) | ◐ | The CLI `erase` is repeater/room only and already ships behind a confirmation. **No companion-side factory reset is implemented** — but ⚠ **the stated reason was wrong** (corrected 2026-08-06): this row claimed "the companion protocol has no such command", and firmware defines `CMD_FACTORY_RESET = 51`. It is unimplemented by choice, not by absence, and it is the one command where being wrong wipes the radio — so it wants a typed confirmation and a hardware test, not a quick wire-up. |
| `ExportConfigurationScreen`, `ImportConfigurationScreen` | Settings → App → Backup | ✅ | Closed 2026-08-01. Plain half = settings/regions/contact keys+names/channel names; sealed half = PSKs, passwords, identity seed under AES-256-GCM + PBKDF2(600k). Encoding secrets without a passphrase is refused, not silently dropped. |
| `PurgeDataScreen` | Settings → App → Purge local data | ✅ | Closed 2026-08-01. Explicit list of what goes and what doesn't; type-the-word confirmation; forgetting keys is a separate opt-in. Does not touch the radio. |

## 2. Contacts & nodes

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `ContactScreen` (detail) | Contact sheet | ✅ | Closed 2026-08-01: key + copy, last advert heard, hops, out path, hash size, distance. |
| `AddContactScreen` | Import contact QR, discovery inbox | ✅ | |
| "Add Contact from Internet" | — | ⛔ | Requires their directory service. |
| `ShareContactScreen` | Share contact QR | ✅ | Byte-identical `meshcore://contact/add?…` payload. |
| `RenameContactScreen` | Rename | ✅ | |
| `ContactSettingsScreen` | Contact sheet actions | ◐ | Need to see what else lives there. |
| `ContactPermissionsScreen` | Contact sheet → Permissions | ✅ | Closed 2026-08-01. The CONTACT_FLAG_TELE_* bits, plus a local notification mute. Each switch states whether the global policy makes it decisive — a per-contact grant cannot widen a global Deny, and a switch that silently does nothing is worse than no switch. |
| `DiscoverScreen`, `DiscoverNodesScreen`, `DiscoveredContactScreen` | Nodes → New tab + Discover nearby repeaters | ✅ | Closed 2026-08-01. Active broadcast discovery alongside the passive advert inbox. ⚠ Responders identify by key PREFIX, so only already-known contacts are reported and a silent node is not reported as absent. |
| `DiscoverMapScreen` | Map | ◐ | |
| `KnownRepeatersBottomSheet` | Nodes → Repeaters | ✅ | |
| `ContactSelectorBottomSheet` | — | ◐ | We navigate instead of picking. |
| `HeardViaScreen` | Message info → "Arrived via" | ✅ | Done 2026-08-01. The route a message came in on, in travel order, from the RX-log packet (`HeardVia`). Exact for channel messages; correlated (and refused when ambiguous) for direct ones. |
| `HeardRepeatsScreen` | Nodes → ⋮ → Who repeats me | ✅ | Closed 2026-08-06. ⚠ **This row's own reasoning was wrong** — see below. |

**Correction (2026-08-06): "not answerable from the RX log alone" was false.**
That sentence sat in the table for five days and was the only reason the
row stayed open. The firmware says otherwise, in one line:
`Dispatcher::checkRecv()` calls `logRxRaw()` immediately after
`recvRaw()` — **before** `tryParsePacket`, before the seen-table check,
before any routing decision. So the client is handed every packet the
radio demodulates, *including* the ones the mesh layer is about to
discard as duplicates. A repeater rebroadcasting our own packet is
exactly such a duplicate: `Mesh.cpp` marks our outbound packets seen
precisely so the radio will not re-transmit them ("mark this packet as
already sent in case it is rebroadcast back to us"). Dropped for routing,
still logged for us.

What ships uses our **own signed advert coming home**, and the choice is
what makes it honest rather than merely plausible:

- An ADVERT payload carries the sender's **full 32-byte public key**, so
  recognising our own is an exact comparison — not the one-byte
  `src_hash` narrowing a direct message would offer.
- It is Ed25519-signed over that key and only verified adverts are
  accepted, so **forging a repeat of our advert needs our private key**.
  Nobody in range can invent a relay or inflate the list.
- It is triggerable: the screen's button sends a flood advert, making
  this a measurement you take rather than a report that fills in if
  someone happens to message you.
- ⚠ Channel messages are deliberately NOT used, though the engine can
  decrypt them: a channel message's only claim of authorship is its
  sender *name*, which is unauthenticated display text (§12).
- ⚠ **Heard us ≠ we heard it.** Hop 0 pulled our transmission out of the
  air; the LAST hop is the one whose transmission we demodulated, so it
  is the only hop with a measured SNR. The screen states each row's
  direction rather than implying both.
- ⚠ The list is a **floor, not coverage**. A repeater that carried our
  traffic onward without a copy returning cannot appear at all, and the
  screen says so.

## 3. Messaging

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `ContactMessagesScreen`, `ChannelMessagesScreen` | Conversation | ✅ | Reactions, quote-replies, links, hops/SNR, info sheet, delete, resend, drafts. |
| `MessageSettingsScreen` | — | ❌ | **Work item.** Message-level preferences. |
| `ChannelMessageRetentionSettingsScreen` | Settings → App → Message retention | ✅ | Closed 2026-08-01. Forever / N days / newest N, global with a per-channel override. Count-trimming orders by ARRIVAL, not the sender-claimed timestamp. |
| `NotificationSettingsScreen`, `ChannelNotificationSettingsScreen` | Global toggle → per-kind → per-thread mute | ✅ | Closed 2026-08-01. Three levels, narrowest last. A muted thread still counts unread: silence is about interruption, not about hiding that something arrived. |
| `BlockedChannelSendersScreen` | Settings → App → Blocked contacts + Hidden channel names | ✅ | Closed 2026-08-01. ⚠ **Corrected:** block-by-key is impossible for channels — see below. DMs block on the full public key; channel names are a *filter*, labelled as one. |
| `RepeatSettingsScreen` | Automatic retry with airtime backoff | ◐ | |

**Correction to this section's own ⚠ (2026-08-01).** The row above used to
say "block by *key*, not by the sender name". That is right for direct
messages and **not possible for channels**: a MeshCore group message is
`"name: text"` inside the channel ciphertext and carries no sender key at
all (MESHCORE_PROTOCOL §9/§10). A channel has no per-sender identity —
anyone holding the PSK can write any name. So the feature ships as two
mechanisms with two different promises, rather than one word covering
both:

- **Blocked contacts** — matched on the full 32-byte public key. A real
  block: renaming doesn't evade it, and their DMs are dropped before
  they become rows (a message stored-and-hidden is still on the phone,
  still in a backup). A 6-byte prefix is never accepted as an entry: 48
  bits would block everyone who collides. An *unresolved* sender is
  never treated as blocked, or traffic from anyone not yet a contact
  would vanish silently.
- **Hidden channel names** — matched on the display name, exact after
  case-folding (a substring match would make its effects unpredictable).
  Shipped as a noise filter and labelled as one, with the caveat string
  living in `BlockList` next to the matching code so the two can't
  drift. Calling this "blocking" is the actual security bug available
  here: a user who believes someone cannot reach them behaves
  differently from one who knows they've hidden a name.

## 4. Channels

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `AddChannelScreen`, `CreateChannelScreen`, `AddExistingChannel` | Channel add sheet | ✅ | `#hashtag` derivation, explicit PSK, generated key. |
| `ChannelSettingsScreen`, `RenameChannelScreen` | Channel edit sheet | ✅ | |
| `ShareChannelScreen` | Share channel QR | ✅ | Closed 2026-08-01. Their `meshcore://channel/add?…&channel_secret=…` form. ⚠ Behind a confirmation: the code IS the key. |
| `ChannelParticipantsScreen` | Conversation → Names seen | ✅ | Closed 2026-08-01, as the caveat demanded. Titled "Names seen", counts appearances not people, and nothing is tappable to message someone — there is no identity behind a channel name to message. The only action offered is Hide. |
| `ChannelSelectorBottomSheet` | — | ◐ | |

## 5. Rooms

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `RoomLoginScreen` | Admin/guest login | ✅ | Password sealed in the Keystore. |
| `RoomManagementScreen` | Repeater admin (shared UI) | ◐ | **Work item.** Room-specific management surface. |
| `RoomServerReadOnlySettingsScreen` | Settings form (role-filtered) | ✅ | Reassessed 2026-08-01: guest sessions already see only read commands, asserted by test. |
| Room post authorship | Author label + avatar | ✅ | Closed 2026-08-01. ⚠ We show `Name [PREFIX]` and refuse to guess on collision. |

## 6. Repeater administration

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `RepeaterLoginScreen` | Login (admin + guest) | ✅ | |
| `RepeaterCommandHelpScreen` | Admin → Help tab | ✅ | Closed 2026-08-01. Role- AND session-filtered, searchable; tapping loads the console rather than running. |
| `RepeaterHealthScreen` | Status panel | ◐ | Battery, uptime, queue, RSSI/SNR, airtime, packet counts. Theirs may chart over time. |
| `AccessControlScreen` (read) | Admin → Status → Access list | ✅ | Closed 2026-08-01. Read-only by design. Note: firmware on the test repeater answers `??: acl`, i.e. no ACL support — unparsed replies are shown verbatim rather than rendered as an empty list. |
| `AccessControlAddUserScreen` (write) | — | ❌ | **Work item, blocked.** Writing an ACL entry grants control of a repeater; the set syntax couldn't be confirmed from their binary and needs a node that supports ACLs to verify against. Not guessing. |
| `ChangeAdminPasswordScreen`, `ChangeGuestPasswordScreen` | Settings form (CLI, masked + confirmed) | ✅ | Reassessed 2026-08-01: functionally complete via the role-filtered settings form, which masks input and confirms. A dedicated screen would add surface, not capability. |
| `ChangeAdvertIntervalsScreen` | Settings form (CLI) | ✅ | Reassessed 2026-08-01: `advert.interval` and `flood.advert.interval` are both in the form. |
| `ChangeOwnerInfoScreen`, `ViewOwnerInfoScreen` | Settings form (`owner.info`) | ✅ | Reassessed 2026-08-01: read and write both present. |
| `ChangePositionScreen`, `PositionSettingsScreen`, `PositionSelectorScreen` | Settings → advert lat/lon + **Pick on map** | ✅ | Closed 2026-08-01. Crosshair picker; warns that the position is broadcast mesh-wide. |
| `ChangeIdentityKeyScreen`, `ManageIdentityKeyScreen` | Repeater admin → Identity tab | ✅ | Closed 2026-08-01, carefully. ⚠ Three refusals shape it: the key is never shown unasked (reading it puts it back on the air, with its own confirmation), it is never stored by this app, and replacing it needs a typed confirmation after the consequences are listed. Degenerate keys (all one byte) are refused despite being structurally valid. |
| `ClockDriftScreen` | Settings → Clock | ✅ | Closed 2026-08-01. Drift shown with direction, flagged past the 30 s auto-correct threshold. |
| `RepeaterNeighboursMapScreen` | Admin → Status → Neighbours | ◐ | Closed 2026-08-01 as a LIST, not a map. ⚠ Entries carry a 4-byte key prefix, so a name is offered only on a unique match and "(N matches)" otherwise. The table is hearsay — what the repeater says it hears, relayed by it. A neighbours MAP is still a list-only row: the §9 polyline work it was waiting on now exists, but a neighbour table is a star around one repeater rather than a route, so it needs its own overlay rather than reusing the route one. |
| `NoiseFloorScreen` | Admin → Status → Noise floor | ✅ | Closed 2026-08-01. 5 s polling with min/max/history while the screen is open; stops on leave, since each sample costs airtime on someone else's node. |
| Firmware update (`start ota` + hand-off to the nRF DFU app) | Repeater hub → Firmware; Settings → Firmware for the connected radio | ✅ | **Added 2026-08-13, ahead of them.** The mainstream app sends `start ota` and leaves you to Nordic's DFU app with the right packet-receipt settings. This does the transfer itself: Nordic legacy DFU spoken in `shared/firmware`, the board confirmed by name, downloads checked against the checksum the release published. ⚠ nRF52 only and by nature local — `start ota` crosses the mesh, the image cannot. ESP32's WiFi-hotspot path is out of scope by decision (this app does not use WiFi, and a browser does it properly). |
| `RxLogScreen` | Diagnostics log | ⚠ | **Not a gap — a deliberate difference.** Theirs is an always-available packet log; ours is off by default and redacts `set prv.key`, passwords and long hex before a line is stored. Reclassified 2026-08-01: this row was never going to become ✅ by copying them. |

## 7. Sensors & telemetry

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `SensorLoginScreen`, `SensorManagementScreen` | Nodes → Sensors tab → admin (login/CLI/settings/telemetry) | ✅ | Closed 2026-08-01. `NodeRole.Sensor` added to the CLI catalog with `sensor get/set/list`; sensors see the universal node commands but not repeater/room or region surface. |
| `ContactTelemetryScreen` | Contact sheet → Telemetry | ✅ | Closed 2026-08-01. Silence (no telemetry, or no permission) reads as such, not as an error. |
| `TelemetrySettingsScreen` | Mesh policies → telemetry permissions | ◐ | **Work item.** |

## 8. Regions (flood scope)

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `AddRegionScreen`, `RegionSelectorScreen`, `DiscoverRegionsScreen` | Settings → Mesh policies → Regions; per-channel picker in the channel editor | ✅ | Closed 2026-08-01, and **verified against hardware the same day** — discovery broadcast, anon request, tag correlation and body parse all confirmed end-to-end (see MESHCORE_PROTOCOL §7). ⚠ Discovery never rewrites a contact's stored path — see below. |
| `RepeaterAddRegionScreen`, `RepeaterManageRegionsScreen`, `RepeaterDefaultRegionScopeScreen` | Repeater admin → Regions tab | ✅ | Closed 2026-08-01. `region get/put/remove/allowf/denyf/default/home/save` behind validation and confirmations; role-gated. `region load` deliberately not offered. |

Notes on this block:

- **A region is a routing tag, not a boundary.** The radio hashes the name
  (`SHA256("#name")[0..15]`) and floods only matching packets. Nothing about it is
  authenticated and nothing about it is private — the UI says so on every surface.
- **The flood scope is global radio state**, so a region-scoped channel send is
  set-scope → send → restore, held under one lock. Unscoped sends take the same lock:
  otherwise one could slip inside another channel's scope window and flood into a region
  it was never meant for. A refused scope aborts the send rather than putting the message
  on whatever scope the radio happens to hold, and a failed restore is surfaced
  (`floodScopeStuck`) instead of leaving the radio quietly scoped.
- **Restores put the user's global scope back**, not blank — Settings owns that value.
- **⚠ Discovery does not touch stored paths.** The reference client rewrites the target
  repeater's contact path to force a direct reply and restores it afterwards; we send the
  contact's existing path as the reply path instead. Clobbering a pinned route — and
  leaving it clobbered if the app dies mid-request — is a worse failure than a query that
  goes unanswered.
- **⚠ Nothing discovered is imported.** Region names arrive from unauthenticated nodes,
  so they are canonicalised, capped, and shown for the user to choose from.
- **⚠ Names are validated before they reach a CLI line.** `region load` puts the node into
  a multi-line mode where each following line is a region name, which makes whitespace in
  a name a command-injection vector rather than a cosmetic problem. `region load` itself
  isn't offered: a one-shot CLI message would strand the node in that mode.
- **`region save` is never automatic.** Region edits live in RAM until it runs; the panel
  says so rather than silently making an experiment permanent.

## 9. Map & tools

| Their surface | Us | Status | Notes |
|---|---|---|---|
| Node map | Map tab (osmdroid) | ✅ | Labels, type filter, GPX export, tile cache control. |
| `ToolsScreen` (hub) | — | ❌ | Organisational; follows once there are tools to hub. |
| `TracePathScreen`, `TracePathMapScreen`, `ViewPathScreen`, `SetContactPathScreen` | Routing sheet + per-hop location + map route overlay | ✅ | Closed 2026-08-01, verified drawing a real 2-hop route on hardware. ⚠ An **ambiguous** hop is a gap in the line, never pinned to a guess, and a route with gaps is drawn DASHED so a line you can't fully vouch for doesn't look like one you can. Trace verified working on hardware 2026-08-01: a one-hop route through a live node replies in ~1 s with per-hop SNR. |
| `CoverageMapToolScreen`, `LosMapToolScreen` (+ settings) | — | ⛔ | **Out of scope (2026-08-01, user decision).** RF coverage and line-of-sight modelling is a solved problem with better tools than a phone app can be, and the author maintains their own repo for it. Building a worse one inside a messaging client is not parity, it's duplication. |
| `InternetMapScreen` | — | ⛔ | Uploads/queries node positions via their server. |
| `PrintScreen` | — | ❌ | Low priority. |
| `AddMapMarker`, `Add me to the Map` | — | ❌ | The second one is ⛔ if it publishes to their map. |

## 10. App-level

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `SettingsScreen` | Settings | ✅ | |
| `AboutScreen`, `ChangeLogScreen` | Settings → About | ✅ | Closed 2026-08-01. States the app's checkable promises (one outbound connection, no accounts/analytics, channels obfuscated-not-secure) rather than slogans. Changelog ships in-app — a changelog behind a link is one you can't read in the field. |
| `LanguageSelectorScreen` | — | ❌ | We're English-only. Their app ships 30+ locales. |
| `DeveloperMenuScreen`, `DeveloperModePasswordScreen`, `ExperimentalSettingsScreen` | — | ❌ | Low priority. |
| `DebugLogsScreen` | Diagnostics | ✅ | |
| Theme | Settings → Theme | ✅ | |

## 11. Out of scope — and why

| Their surface | Why not |
|---|---|
| `ManagePurchasesScreen`, `TestPurchasesScreen`, `ProFeatureWaitScreen`, `OfflineProductActivationScreen`, `BillingService` | In-app purchases. This app has no commercial layer and no Play Billing dependency. |
| `BugReportingSettingsScreen`, `BugsnagManager` | Crash telemetry to a third party. The app makes no outbound connections except map tiles; that's a stated guarantee. |
| `InternetMapScreen`, "Add Contact from Internet", "Add me to the Map" | Server-mediated. No servers, no accounts. |
| `AppInfoService`, `DeviceIdService` | Device identifiers for analytics. |
| `OnlineMapManager` beyond tile fetch | Same reason. |
| `CoverageMapToolScreen`, `LosMapToolScreen` | RF coverage / line-of-sight modelling. Dedicated tools do this properly, the author already maintains one, and a phone-sized approximation of terrain propagation would be confidently wrong in exactly the situations you'd rely on it. Out by decision (2026-08-01), not by effort. |

## 12. Where we deliberately differ (⚠ rows)

These are not gaps to close by copying:

- **Hop hashes are not identities.** A hop is a truncated key hash — two bytes is 16
  bits, cheap to collide. We name a hop only when exactly one contact matches, show
  `(N matches)` otherwise, and never silently pick.
- **Contact cards are unsigned.** Scanning one shows the full public key and asks, rather
  than adding silently. Signed adverts still go through the verifying import path.
- **Channel sender names are display text.** Never used for identity, mutation, or
  echo suppression — so anything built on "who is in this channel" has to be framed as
  "names seen", not membership.
- **Channels are obfuscated, not secure** (AES-ECB + 2-byte MAC), and the UI keeps
  saying so.
- **Diagnostics are off by default and redact secrets** (`set prv.key`, passwords, long
  hex) before a line is stored.
- **Region discovery leaves stored paths alone.** A pinned route is user state; we read it
  to build the reply path rather than overwriting it and hoping to restore it.
- **Regions are routing, not privacy.** Scoping a channel changes which repeaters carry
  it, not who can read it — and every region surface says exactly that.

## 13. Suggested order

Grouped so each block is shippable:

1. ~~**Finish what's started** — channel QR export, per-contact telemetry view, position
   picker on a map, clock drift display.~~ **Done 2026-08-01.**
2. ~~**Admin depth** — access control, command help, repeater regions, noise floor.~~
   **Done 2026-08-01**, except `AccessControlAddUserScreen` (§6, blocked on a node that
   supports ACLs).
3. ~~**Safety & data** — config export/import (secret-safe), purge data, retention,
   blocked senders (by key).~~ **Done 2026-08-01**, with the block-by-key row corrected
   (see §3) — channels carry no sender key, so that half ships as a labelled filter.
4. ~~**Onboarding & polish** — setup flow, radio presets, About/changelog, tools hub.~~
   **Done 2026-08-01** except the tools hub, which is deliberately skipped: every tool
   (trace, noise floor, discovery, regions) is reachable from the node it applies to, and
   a hub would duplicate navigation rather than add capability. Revisit if a tool appears
   that belongs to no particular node.
5. **Bigger asks** — sensor nodes ✅, neighbours ✅ (as a list), path map rendering ◐.
6. **Revisit deferrals** — what is genuinely left, and why:
   - ~~**Heard-via / heard-repeats** (§2), and the visual the user actually wants: "how
     did this message get to me".~~ **Done 2026-08-01.** The message sheet grows an
     "Arrived via" section listing the route in travel order — sender at the top, this
     radio at the bottom — recovered from `PUSH_CODE_LOG_RX_DATA`, which is the only frame
     carrying the full path. The two halves are not equally certain and the code keeps
     them apart (`HeardVia`): a **channel** message is EXACT, because the engine decrypts
     that very packet; a **direct** message must be correlated on sender byte + hop count
     + time, and a route is claimed only when exactly one packet fits. Two plausible
     packets yields "isn't known" — a route credited to the wrong message looks exactly
     like a correct one. The reversal warning is honoured: the routing sheet's "Reply the
     way they reached me" reverses the arrival path hop-by-hop before offering it.
     Remaining: heard-repeats (which repeaters re-broadcast OUR traffic) is a different
     question and still open.
   - ~~**Hop selection is free-text hex** (§9).~~ **Done 2026-08-01**, verified on the
     radio. Tap a known repeater to append it, reorder with ▲/▼, remove with ×; the hex is
     derived, never typed. Hops carry the node's **full public key** and compute their
     hash at the current width on demand (`HopSelection`), so a `DEVICE_INFO` that lands
     after the sheet opens corrects the display instead of pinning a wrong route — the
     fourth instance of the width defect was in the old picker itself, which hardcoded a
     1-byte hop and so inserted half a hop on this 2-byte mesh. Free-text survives behind
     "Enter hops as hex" for routes copied from elsewhere. An ambiguous hop deliberately
     stays a bare hash rather than adopting one candidate's key.
   - ~~**Path history needs cleaning** (§9).~~ **Done 2026-08-01**, verified on the radio
     (repair pass: 3 rows restated, 4 junk rows dropped). Schema v7 adds
     `path_history.hashWidth`; every write site now stores a HOP count instead of a byte
     count. Rows predating the column are repaired against the mesh's width once
     `DEVICE_INFO` arrives and deleted if they still don't parse — deletion is the right
     outcome because the routing sheet offers these as routes to PIN. Table is capped at
     20 paths per contact, with the flood route pinned so it is never pruned.
   - **`AccessControlAddUserScreen`** (§6). Still blocked on a node that supports ACLs.
   - **`LanguageSelectorScreen`** (§10). A translation programme, not a coding task.
     Machine-translating safety-critical warning copy into languages nobody here can
     check would be worse than shipping English.
   - **`MessageSettingsScreen`, `ContactSettingsScreen`** — cannot be specified without
     seeing the running mainstream app; the inventory gives class names, not behaviour.
   - **`PrintScreen`, `AddMapMarker`, developer menu** — low priority, no security weight.
