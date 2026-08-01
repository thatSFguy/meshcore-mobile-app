# Feature parity vs the mainstream MeshCore Android app

**Policy (2026-08-01):** the mainstream app — `com.liamcottle.meshcore.android`,
"MeshCore", v1.47.0 — is the **floor** for this app's feature set. Anything it does that
we don't is a gap to close, unless it's explicitly out of scope below. This supersedes
the original "pruned from MeshCore Open" scoping in [`SCOPE.md`](SCOPE.md) for anything
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
| `SetupScreen` (first-run onboarding) | — | ❌ | **Work item.** We drop the user straight into an empty app. |
| `SuggestedRadioSettingsSelectorBottomSheet` (radio presets) | Settings → Radio (manual fields) | ◐ | **Work item.** Presets per region/profile, rather than typing freq/BW/SF/CR. |
| `FactoryResetScreen` | — | ❌ | **Work item**, low risk: it's a CLI command behind a confirmation. |
| `ExportConfigurationScreen`, `ImportConfigurationScreen` | — | ❌ | **Work item.** Config backup/restore. Must never export secrets in the clear — see §11. |
| `PurgeDataScreen` | Clear thread / Clear console | ◐ | **Work item.** A single "purge everything local" with an explicit list of what goes. |

## 2. Contacts & nodes

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `ContactScreen` (detail) | Contact sheet | ✅ | Closed 2026-08-01: key + copy, last advert heard, hops, out path, hash size, distance. |
| `AddContactScreen` | Import contact QR, discovery inbox | ✅ | |
| "Add Contact from Internet" | — | ⛔ | Requires their directory service. |
| `ShareContactScreen` | Share contact QR | ✅ | Byte-identical `meshcore://contact/add?…` payload. |
| `RenameContactScreen` | Rename | ✅ | |
| `ContactSettingsScreen` | Contact sheet actions | ◐ | Need to see what else lives there. |
| `ContactPermissionsScreen` | — | ❌ | **Work item.** Per-contact telemetry/ACL permissions. |
| `DiscoverScreen`, `DiscoverNodesScreen`, `DiscoveredContactScreen` | Nodes → New tab | ◐ | Ours is a passive inbox of verified adverts; theirs has an active discovery flow. |
| `DiscoverMapScreen` | Map | ◐ | |
| `KnownRepeatersBottomSheet` | Nodes → Repeaters | ✅ | |
| `ContactSelectorBottomSheet` | — | ◐ | We navigate instead of picking. |
| `HeardViaScreen`, `HeardRepeatsScreen` | — | ❌ | **Work item.** Which repeater a node was heard through. |

## 3. Messaging

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `ContactMessagesScreen`, `ChannelMessagesScreen` | Conversation | ✅ | Reactions, quote-replies, links, hops/SNR, info sheet, delete, resend, drafts. |
| `MessageSettingsScreen` | — | ❌ | **Work item.** Message-level preferences. |
| `ChannelMessageRetentionSettingsScreen` | — | ❌ | **Work item.** Retention/auto-prune. Also a privacy feature: history that isn't kept can't leak. |
| `NotificationSettingsScreen`, `ChannelNotificationSettingsScreen` | Per-channel mute, global toggle | ◐ | **Work item.** Finer control. |
| `BlockedChannelSendersScreen` | — | ❌ | **Work item.** ⚠ Block by *key*, not by the sender name, which is unauthenticated. |
| `RepeatSettingsScreen` | Automatic retry with airtime backoff | ◐ | |

## 4. Channels

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `AddChannelScreen`, `CreateChannelScreen`, `AddExistingChannel` | Channel add sheet | ✅ | `#hashtag` derivation, explicit PSK, generated key. |
| `ChannelSettingsScreen`, `RenameChannelScreen` | Channel edit sheet | ✅ | |
| `ShareChannelScreen` | Share channel QR | ✅ | Closed 2026-08-01. Their `meshcore://channel/add?…&channel_secret=…` form. ⚠ Behind a confirmation: the code IS the key. |
| `ChannelParticipantsScreen` | — | ❌ | ⚠ **Work item with a caveat**: channel "participants" can only ever be *names seen*, which are unauthenticated. Ship it as "senders seen", never as a membership list. |
| `ChannelSelectorBottomSheet` | — | ◐ | |

## 5. Rooms

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `RoomLoginScreen` | Admin/guest login | ✅ | Password sealed in the Keystore. |
| `RoomManagementScreen` | Repeater admin (shared UI) | ◐ | **Work item.** Room-specific management surface. |
| `RoomServerReadOnlySettingsScreen` | Settings form (role-filtered) | ◐ | |
| Room post authorship | Author label + avatar | ✅ | Closed 2026-08-01. ⚠ We show `Name [PREFIX]` and refuse to guess on collision. |

## 6. Repeater administration

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `RepeaterLoginScreen` | Login (admin + guest) | ✅ | |
| `RepeaterCommandHelpScreen` | Admin → Help tab | ✅ | Closed 2026-08-01. Role- AND session-filtered, searchable; tapping loads the console rather than running. |
| `RepeaterHealthScreen` | Status panel | ◐ | Battery, uptime, queue, RSSI/SNR, airtime, packet counts. Theirs may chart over time. |
| `AccessControlScreen` (read) | Admin → Status → Access list | ✅ | Closed 2026-08-01. Read-only by design. Note: firmware on the test repeater answers `??: acl`, i.e. no ACL support — unparsed replies are shown verbatim rather than rendered as an empty list. |
| `AccessControlAddUserScreen` (write) | — | ❌ | **Work item, blocked.** Writing an ACL entry grants control of a repeater; the set syntax couldn't be confirmed from their binary and needs a node that supports ACLs to verify against. Not guessing. |
| `ChangeAdminPasswordScreen`, `ChangeGuestPasswordScreen` | Settings form (CLI) | ◐ | Present but not as first-class screens. |
| `ChangeAdvertIntervalsScreen` | Settings form (CLI) | ◐ | |
| `ChangeOwnerInfoScreen`, `ViewOwnerInfoScreen` | Settings form (`owner.info`) | ◐ | |
| `ChangePositionScreen`, `PositionSettingsScreen`, `PositionSelectorScreen` | Settings → advert lat/lon + **Pick on map** | ✅ | Closed 2026-08-01. Crosshair picker; warns that the position is broadcast mesh-wide. |
| `ChangeIdentityKeyScreen`, `ManageIdentityKeyScreen` | — | ❌ | ⚠ **Work item, carefully.** Identity key handling is the highest-consequence screen in the app. |
| `ClockDriftScreen` | Settings → Clock | ✅ | Closed 2026-08-01. Drift shown with direction, flagged past the 30 s auto-correct threshold. |
| `RepeaterNeighboursMapScreen` | — | ❌ | Deferred in SCOPE.md; revisit under this policy. |
| `NoiseFloorScreen` | Admin → Status → Noise floor | ✅ | Closed 2026-08-01. 5 s polling with min/max/history while the screen is open; stops on leave, since each sample costs airtime on someone else's node. |
| `RxLogScreen` | Diagnostics log | ◐ | Ours is redaction-aware and off by default; keep that. |

## 7. Sensors & telemetry

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `SensorLoginScreen`, `SensorManagementScreen` | — | ❌ | **Work item.** Sensor nodes are a first-class node type we don't handle. |
| `ContactTelemetryScreen` | Contact sheet → Telemetry | ✅ | Closed 2026-08-01. Silence (no telemetry, or no permission) reads as such, not as an error. |
| `TelemetrySettingsScreen` | Mesh policies → telemetry permissions | ◐ | **Work item.** |

## 8. Regions (flood scope)

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `AddRegionScreen`, `RegionSelectorScreen`, `DiscoverRegionsScreen` | Settings → Mesh policies → flood scope | ◐ | **Work item.** We set a scope; they manage named regions. |
| `RepeaterAddRegionScreen`, `RepeaterManageRegionsScreen`, `RepeaterDefaultRegionScopeScreen` | — | ❌ | **Work item.** Region management on a repeater. |

## 9. Map & tools

| Their surface | Us | Status | Notes |
|---|---|---|---|
| Node map | Map tab (osmdroid) | ✅ | Labels, type filter, GPX export, tile cache control. |
| `ToolsScreen` (hub) | — | ❌ | Organisational; follows once there are tools to hub. |
| `TracePathScreen`, `TracePathMapScreen`, `ViewPathScreen`, `SetContactPathScreen` | Routing sheet + trace | ◐ | We trace and pin paths; no map rendering of the route. **Work item.** |
| `CoverageMapToolScreen`, `LosMapToolScreen` (+ settings) | — | ❌ | Deferred in SCOPE.md; revisit. |
| `InternetMapScreen` | — | ⛔ | Uploads/queries node positions via their server. |
| `PrintScreen` | — | ❌ | Low priority. |
| `AddMapMarker`, `Add me to the Map` | — | ❌ | The second one is ⛔ if it publishes to their map. |

## 10. App-level

| Their surface | Us | Status | Notes |
|---|---|---|---|
| `SettingsScreen` | Settings | ✅ | |
| `AboutScreen`, `ChangeLogScreen` | — | ❌ | **Work item**, small. |
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

## 13. Suggested order

Grouped so each block is shippable:

1. ~~**Finish what's started** — channel QR export, per-contact telemetry view, position
   picker on a map, clock drift display.~~ **Done 2026-08-01.**
2. **Admin depth** — access control, command help, repeater regions, noise floor.
3. **Safety & data** — config export/import (secret-safe), purge data, retention,
   blocked senders (by key).
4. **Onboarding & polish** — setup flow, radio presets, About/changelog, tools hub.
5. **Bigger asks** — sensor nodes, path/coverage map rendering, neighbours map.
6. **Revisit deferrals** — LOS/coverage overlays, neighbours, discovery-as-separate:
   all deferred under the old scope, all now in-bounds under this policy.
