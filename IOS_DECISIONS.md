# iOS Phase 2 — decisions log

Decisions made while bringing the iOS app from the Phase 1 skeleton toward
Android parity, starting 2026-11-17. Each one says what was chosen, what
the alternative was, and why. Anything here is open to reversal by the
author; it is written down so it is not reversed *by accident*.

## How the work is verified

**Logic goes in `shared/`, Swift stays thin.** Nothing Apple compiles on
the development machine, so Swift written here is checked only by CI.
Every rule that decides behaviour (what becomes a message row, when a DM
is retried, what counts as delivered) is therefore written in
`shared/commonMain` and tested on the JVM, where it can be run in seconds
and mutation-checked. The Swift layer only mirrors state and draws it.
The alternative — porting Android's logic into Swift line by line — would
have produced a second, untested reading of rules that already have tests.

**CI compiles it from a branch, not `main`.** The iOS workflow runs on
pushes to `main` and on `workflow_dispatch`. Work is pushed to the branch
`ios-phase2` and CI is dispatched against it, so a red Swift build never
lands on `main`. Merging is left to the author. (Merged 2026-11-18, a
fast-forward, after CI run 44239195936 went green on the whole branch.
Keep the pattern for further iOS work: branch, dispatch, merge on green.)

## Messaging (`shared/.../messaging/`)

1. **The DM retry ladder was lifted into `shared` as `DirectSend`**, not
   rewritten for iOS. Same rules as Android's
   `MessageRepository.sendDirectWithRetry`: three attempts, the last one
   resetting the path and flooding, AckTimeout's distance-aware waits, one
   ACK watch for the whole send, and the 30 s late-ACK grace.
   **Android was NOT switched over to it.** Android's loop is
   hardware-proven and interleaves Room writes and path scoring; moving it
   in the same change as an iOS bring-up would put a working path at risk
   for no user-visible gain. Follow-up: have Android delegate to
   `DirectSend` (passing its path scoring in `onStatus`), then delete the
   copy.

2. **Two small departures from Android's loop, both deliberate:**
   - the ACK collector starts `UNDISPATCHED`, so it is subscribed before
     the first attempt goes out. A plain `launch` (as on Android) leaves a
     window in which a very fast ACK is missed. Harmless in practice on
     LoRa, but it is a real race and the fix costs nothing.
   - when the radio refuses a *later* attempt outright, the message still
     gets the late-ACK grace for the attempts already on the air. Android
     returns Failed immediately; an earlier attempt can still be delivered.

3. **No path scoring on iOS.** Android records per-route success and
   failure for its routing sheet. iOS has no routing sheet yet, so the
   ladder reports status only. Add it when the routing UI is ported.

4. **CLI replies (txt type 1) are dropped**, not stored in the DM thread.
   They belong to a repeater console, which iOS does not have yet. Storing
   them now would fill a contact's chat with `> 910.525` lines.

5. **Reactions are attached, not shown as text.** An `r:<hash>:<idx>`
   message is matched to its target with the same `ReactionRouting` rules
   Android uses, counted once per content key, and drawn as a chip under
   the bubble. A reaction that matches nothing is consumed silently rather
   than surfacing as a cryptic line. Sending reactions is not built yet.

6. **No block list, no channel-name filter, no DM-request handling yet.**
   All three exist on Android; none has an iOS screen. They are listed so
   their absence is a known gap rather than a silent difference.

7. **Channel sends are unscoped** (`region = nil`), as in Phase 1. Picking
   a flood region changes which repeaters carry a message; iOS has no
   channel-region UI yet, and guessing one would be worse than using the
   radio's own default scope.

## Persistence

8. **A JSON file per radio identity, not SQLDelight.** The iOS README
   planned to port the sibling's SQLDelight scaffolding. Chosen instead:
   `MessageBook` serialises to one document with `kotlinx.serialization`
   (already a dependency), and Swift writes it to Application Support.
   Reasons: no new dependency (the project's own discipline), no schema or
   migration machinery to maintain for what is today one table, and the
   whole model stays testable in `commonTest`. The trade: every save
   rewrites the file. With the per-thread cap (below) that is a few
   hundred KB at worst, written on a 0.5 s debounce. Revisit if a profile
   ever shows the save mattering, or when contacts/paths need storing too.

9. **Encrypted at rest by iOS Data Protection**
   (`completeFileProtectionUntilFirstUserAuthentication`), and excluded
   from iCloud backup. Android uses an encrypted Room database with a
   Keystore-sealed key; Data Protection is the platform-native equivalent
   on iOS — the file is unreadable until the device is first unlocked
   after boot. "Until first unlock" rather than "complete" so a message
   that arrives while the phone is locked in a pocket can still be saved.
   Not backed up because the app's premise is that nothing leaves the
   phone.

10. **History is kept per radio identity** (`book-<first 8 bytes of
    self key>.json`). Android keys every row by self key; connecting a
    different radio must never mix two identities' threads.

11. **An unreadable file is set aside, not overwritten.** `fromJson`
    never throws (a client that will not start is worse than lost
    history), but the Swift side asks `MessageBook.isReadable` first and
    renames a bad file to `…unreadable-<time>` so it can be recovered.
    The Chats screen says so.

12. **Pending rows are restored as Failed.** A message still Pending
    when the app died will never be resolved — its send loop is gone.
    Sent stays Sent: the radio accepted it and delivery is genuinely
    unknown. Delivered is never moved backwards by any later report.

13. **2,000 messages kept per thread as a hard cap**, oldest dropped
    first and only within the same thread, so a busy channel never evicts
    a quiet DM. On top of that, the user's retention choice (shared
    `Retention` presets: forever / days / newest N) is applied when
    history opens, when the choice changes, and on returning to the
    foreground.

13a. **Retention by age uses OUR receive time, not the sender's clock.**
    This differs from Android, whose `deleteThreadOlderThan` compares the
    sender's `timestamp`. The sender's clock is unauthenticated and often
    wrong; a node whose RTC reset to 2020 would have every message deleted
    on arrival under "keep 30 days". **Android should probably change to
    `receivedAt`** — flagged here rather than changed, since it is not
    iOS work.

## State plumbing

14. **Subscriptions replace the 1-second poll.** `FlowWatch.watch(flow,
    scope, onEach)` turns any Flow into a main-thread callback Swift can
    cancel. The poll lagged by up to a second and could never see a
    SharedFlow event — which is why inbound messages never appeared on iOS
    at all.

## Naming

15. **APP_START now says `MeshCoreHardened`** (was `MeshCoreMobile`).
    Kotlin/Native exports no default parameters, so Swift had to name it,
    and it had drifted from the engine default. **Launcher label is
    `MC Hardened`** (was `MeshCore`), matching Android's `app_name`.

## Notifications

16. **Local notifications for new inbound messages**, never for a copy
    of one already held and never for the thread on screen (the shared
    session decides; Swift only posts). Banners are shown in the
    foreground too, since the user may be on another tab. Permission is
    requested on the first BLE connect, not at launch, so the prompt has
    a reason attached. The message text is included; iOS's own "Show
    Previews" setting governs whether it appears on the lock screen.

## Nodes

17. **The Nodes list is the shared `NodeListModel`, fed by a new
    `ContactNodes`.** Android's rows are Room entities; iOS has none, so
    `ContactNodes` joins the radio's contacts with the message book and
    implements `NodeListItem`. Search, sort and filters are therefore the
    same code on both platforms. The "Nearest" sort is hidden: it needs
    this phone's position, which iOS does not read yet.

18. **Reachability on iOS uses less evidence than Android**, and says
    less rather than more: a delivered DM is the proof (timed by a new
    `deliveredAtMs`), a stored route means they heard us, a non-zero
    advert timestamp means we heard them. "Repeats your messages" is
    never claimed — it needs the route history iOS does not keep.

19. **Tapping a repeater or room opens its detail sheet**, not an admin
    hub — repeater admin is not ported. A chat contact opens its thread;
    the detail is a swipe or a long-press away, as on Android.

20. **Remove-from-radio asks first.** It is the only destructive radio
    action on iOS so far, and it is not undoable from the app.

## Connection

21. **Every link runs under the shared `ReconnectSupervisor`**, and the
    last radio is remembered with the shared `ConnectionMemory` and
    restored at launch once Bluetooth reports poweredOn ("Reconnect
    automatically", default on, matching Android). A fresh
    `IosBleTransport` is built per attempt; iOS never times out a pending
    connect, so an out-of-range radio simply waits.

22. **Scanning disconnects the current radio.** One `CBCentralManager`
    has one delegate. A connected transport holds it, and the scanner
    must take it back to see anything — which silences the transport.
    The alternative, a second central for scanning, is allowed by iOS but
    doubles the state to reason about for the rare case of switching
    radios. The Settings footer says so. (This also fixed a real bug: a
    scan after any connection found nothing, because the transport kept
    the delegate.)

23. **The store owns the scanner** (it was a view's `@StateObject`, so
    leaving Settings could tear down the central a live transport used).

## Radio settings

24. **Read-only for now.** Settings shows the radio through a new shared
    `RadioFacts` (frequency, bandwidth, SF, CR, TX power, battery,
    firmware, hop-hash width). Editing radio parameters is the action
    that can make a node deaf to its mesh, so it waits for a port that
    can be driven on hardware rather than written blind.

## Channels

25. **The join rules moved into shared `ChannelJoin`** — out of
    Android's ViewModel, where they were untestable. Android still uses
    its own copy; same follow-up as `DirectSend`.

26. **Links ask for a region iOS cannot apply → join unscoped, and say
    so.** Refusing would block the person; joining silently would flood
    wider than the sharer intended. The sentence names the region.

27. **`meshcore://` links open a confirmation sheet**, held until the
    radio is ready (shared `LinkInbox`), and are applied only on a tap: a
    link from another app is not authority to write to the radio. What
    the sheet says comes from a new shared `LinkImport`: contact cards
    (marked as *typed* names anyone could claim), signed adverts (marked
    as signed — and refused outright if the signature fails, never
    downgraded to an unsigned card), channels (with the read-everything
    warning), and plain refusals for your own key, a contact already
    held, and radio-settings codes (not applied on iOS yet).

28. **The share link is behind a "Show" tap and the warning**, as
    `ShareUri.encodeChannel` requires: anyone with it reads every message,
    past ones included, with no revocation.

## Contacts, reactions, blocking

29. **QR codes use Apple's own frameworks** — CoreImage to draw,
    VisionKit's `DataScannerViewController` to read — so iOS adds no QR
    dependency. A scanned code goes to the same `LinkImport`
    confirmation as an opened link, including codes that are not
    MeshCore at all (explained rather than silently ignored).

30. **Reactions are sent from the message menu**, quick row only (the
    shared `Reactions.QUICK`). The wire hash is computed by the same rule
    incoming reactions are matched by. A DM reaction rides the retry
    ladder but shows no status — it has no bubble to show it on.

31. **Blocking is per phone, not per radio identity.** Stored in
    `UserDefaults`; Android keeps its block list in preferences too. A
    blocked DM is dropped before it becomes a row, so blocking does not
    retroactively delete history — deleting the thread does that.

## Repeater admin

32. **Sign-in, Status and Console are ported; the other hub tiles are
    listed but marked "On Android only, for now".** Which tiles appear is
    still the shared `repeaterHubTiles`, so a guest sees the same hub on
    both platforms. Settings/Regions/Identity/Firmware write to someone's
    infrastructure and wait for a hardware session.

33. **No repeater password is stored on iOS.** Android keeps them in the
    Keystore. iOS sessions live in memory for the app's lifetime; since a
    repeater's ACL entry never expires, a blank-password sign-in usually
    works again anyway (CLAUDE.md). A Keychain-backed "remember" can come
    later — not storing a secret is the safe default while untested.

34. **The console redacts what it displays** (`RepeaterAdmin.displayed`:
    `set prv.key`, `… password`, long hex) even though it is never
    persisted — a screen gets screenshotted. Same patterns as Android's
    diagnostics redaction. The console is admin-only by the shared tile
    rule, matching the firmware (a guest's CLI is dropped silently).

35. **`RepeaterAdmin` exists only because Kotlin/Native drops default
    arguments.** It forwards to the engine with the engine's own retry
    counts and budgets so Swift never restates a default.

## Diagnostics and icon (added 2026-11-18, after the first run on an iPhone)

36. **Diagnostics are off by default, and the unified-log mirror follows
    the same toggle.** The first hardware session had nothing to read:
    every log sink in the store was `{ _ in }`. `DiagnosticsLog.swift`
    now takes the engine, supervisor, TCP and `MessagingSession.log`
    lines, redacts them with the shared `LogRedaction` (moved out of
    Android's `DiagnosticsLog`, which now delegates to it), keeps the
    last 500 in memory, and mirrors them to the unified log
    (subsystem `io.github.thatsfguy.meshcore`, readable tethered with
    `idevicesyslog -p iosApp`). Android mirrors to logcat only in debug
    builds; iOS ships one Release build from CI, so the toggle is the
    gate instead. Lines are `.public` because they are already redacted.
    `MessagingSession` logs status, attempt, ACK, hops, SNR and 12-hex
    key prefixes — never message text or a channel sender's name (pinned
    by `MessagingSessionTest`).

37. **The app icon is the Android launcher glyph**, rendered to the one
    1024×1024 PNG iOS needs by `tools/render-ios-icon.py` from the same
    vector coordinates, cropped to the central 72 of the 108 viewport —
    what an Android launcher actually shows after masking.

## Phase 3: Settings and this node (2026-11-18)

38. **Settings is built from the shared `settingsGroups()`**, the same
    hub-and-spoke Android uses, with the same live-subtitle functions.
    The first iPhone run's verdict was "the UI is just different"; one
    layout across both apps is the cheapest part of that to fix. The
    Firmware tile is shown but marked "On Android only, for now" — node
    updates are out of iOS scope by the user's decision.

39. **Node settings have their own store** (`NodeSettingsStore`), injected
    beside `MeshCoreStore`. Every write reports the radio's answer as a
    sentence, Android's `deviceAction` pattern.

40. **Ranges, bit layouts and multi-step writes moved to shared before
    any Swift was written**: `NodeSettings`, `OtherParams`,
    `RadioRetune`, `RegionDiscovery`, `RegionList`, `BackupSettings`,
    `BackupRestore`. Android now calls `RadioRetune` too, which fixed a
    real defect: its scanned-code path ignored the radio's answer and
    said "Applied" after a refusal.

41. **Settings codes are applied on iOS** (they were refused in Phase 2),
    through the same confirmation as Android: unsigned and regulatory
    warnings, and the region set only if the person leaves it ticked.
    TX power is never taken from a code.

42. **Flood scope persists as Android's does** (`floodScope` in
    UserDefaults: `""` = cleared, absent = never set), restored into the
    engine at launch. The region list is phone-side. Per-channel regions
    came in Phase 5 (`MessagingSession.channelRegions`, picked on the
    channel's info sheet; forgetting a region clears it from channels).

43. **Auto-add's "useful repeaters" rules are not ported.** They need the
    discovered-node inbox and per-message routes, which iOS does not
    store yet. The five firmware bits are.

44. **Backups work on iOS, including encrypted ones.** AES-256-GCM comes
    from CryptoKit through the existing bridge (pinned on CI to NIST GCM
    test case 16). The only secrets iOS has are the channel keys the
    radio reports; an Android backup's passwords, community secrets and
    identity seed are counted as skipped on restore, not silently
    dropped. Settings travel under Android's names (`BackupSettings`, now
    also Android's own list), so either app restores the other's file,
    and a backup can never switch TCP on behind its warning. PBKDF2's
    600,000 rounds run off the main thread.

45. **Not ported from Android's settings, on purpose:** theme and units
    (iOS follows the system), the map-tiles switch (MapKit, Apple's
    tiles), the developer channel-datagram tool, the map position picker
    (typed coordinates only), and Purge local data — deleting history is
    per-thread on iOS for now.

## Phase 4: routing, paths, heard-via, map (2026-11-18)

46. **The message store keeps what routing needs.** `BookMessage` gained
    the arrival route (inbound) and the relays heard re-broadcasting it
    (outgoing), and keeps `hops = -1` — before, MessageBook discarded it,
    so a routed arrival could only ever read as nothing, never as
    "routed". Old history files load unchanged (new fields default).
    A known route is never overwritten by a later copy; a real hop count
    replaces "routed", never the reverse.

47. **Repeats of our own DMs are credited, not dropped.**
    `MessagingSession` used to ignore `OwnDirectRepeatHeard`. It now goes
    through the shared `MessageRepeats.creditDirect` over sends from the
    last 180 s — the newest send to a matching recipient byte, and none
    when two sends are too close to tell apart (Android's rule).

48. **Path history is a pure shared class** (`PathHistoryBook`), one JSON
    file per radio beside the message history. Rows are remembered as
    the radio reports routes and when the person pins one; scored by
    each DM attempt through a new `DirectSend` `onAttempt` hook (the
    flood fallback scores nothing — it can't say which route the reply
    proved); repaired when the radio reports its hop-hash width.

49. **The routing screen is Android's sheet, as a pushed screen.** Hops
    are `HopSelection` (full keys, hash derived at the mesh's current
    width). Auto/Flood apply on tap and Manual on "Apply path", with no
    confirmation dialogs — as on Android, every action says what
    happened. "Check if they can hear you" lives on the node sheet.

50. **Message info** (long-press → Info) shows Arrived via / Repeated by,
    worded by the shared `MessageDetail`, so iOS says "routed" where it
    used to say nothing and qualifies SNR as last-hop.

51. **The map gained a self pin, type colours and symbols, a labels
    switch, type filters, fit-all, tap-to-open, and GPX export.** GPX is
    now shared (`Gpx`) and Android uses it too; it escapes names AND
    strips characters XML 1.0 cannot carry, which Android's inline
    version did not — a control character in an advert name made the
    whole file unreadable. Its creator is now "MeshCore Hardened".
    Neighbour links need a repeater sign-in and move to Phase 5. The
    message-path and stored-route mini maps are not ported yet.

## Phase 5: repeater admin (2026-11-18)

52. **Neighbour readings live in memory** (`NeighbourBook`, shared), not
    on disk. A reading is only as fresh as the adverts behind it and
    nothing keeps it current; Android persists them, iOS re-reads.

53. **Admin requests go through the shared `RepeaterAdmin`**, which
    restates the engine's defaults and applies Android's path-recovery
    gate (reset the route and retry when silent; once only when not
    signed in or the route is pinned). iOS keeps no password, so the
    re-authentication stage never runs. Answers come back as sentences
    so Swift never names a sealed subtype.

54. **Remote settings are a shared, table-driven form**
    (`RemoteSettingsForm`): what each section reads, how a `get` reply
    becomes fields, and which `set` commands an edit becomes — every
    value validated first. Two defects fixed while lifting it from
    Android's Compose: owner text kept its line breaks (Android flattened
    `|` to spaces and wrote that back), and forwarding delays are checked
    as integers instead of sent raw. A password containing a space is
    not sent — it would split the command. `get acl` is not offered: it
    answers only the serial console; the access list is the binary
    request on Status. `start ota` is out with firmware updates.

55. **Region commands never throw into Swift.** `Regions`' builders throw
    on an invalid name (they are the injection guard); `RegionPanel`
    catches and simply does not offer the step. One refusal rule for
    every region write — silence, "Err" and "??" (Android's panel
    checked only "Err"). The untagged-traffic controls (relay all /
    nearby / refuse) are not ported yet.

56. **Command help is shared** (`CliHelp`, ordered categories — a Kotlin
    map reaches Swift as an unordered dictionary); Android delegates.

57. **Not ported in Phase 5:** identity rekey (read / generate / replace
    a node's private key — the one admin action that can lock you out of
    a node if it goes wrong; it gets its own careful pass and a hardware
    session), untagged-traffic controls, telemetry, the noise-floor
    watch, and scanning a settings code straight onto a remote node.
    The role-mapping difference the spec found (`RepeaterReadout.roleOf`
    maps an unknown type to Companion, Android to Repeater) cannot show:
    iOS opens admin only for repeaters and rooms.

58. **Back burner, by the user's decision (2026-11-18): repeater identity
    rekey and firmware updates.** Neither is ported to iOS. Both can cost a
    node if they go wrong, and the rest of the admin surface gets tried on
    hardware first. The Firmware hub tile stays marked "On Android only".

59. **A shared channel link's region is applied on iOS** (2026-11-18), by
    the same shared `ChannelScopeJoin` rule Android uses, through a
    `ChannelScopeStore` the app supplies: recorded for the slot, the
    region added to the phone's list if new, and never over a region the
    person already chose. Android still runs its own copy of the join
    (identical rules, its own wording); delegating it to `ChannelJoin`
    is deferred as a no-behaviour-change cleanup, as is the DM ladder.

60. **Conversation parity after the first iPhone runs** (2026-11-18):
    swipe-to-reply and `@` mentions (shared `Quoting.prefixFor`,
    `Mentions`; Android's quote builder now delegates), Resend for a
    failed message (`MessagingSession.resend`), "Who repeats me", the
    stale-node sweep (`StaleSweep`, Android delegates), and "Message
    privately" (`DmRequests`). The DM handshake keeps Android's two
    safety rules: accepting adds the requester before answering, and an
    accept only adds someone for a request this radio actually made.
    iOS text fields expose no cursor, so `@` suggestions appear while
    typing at the end of the draft only.

## Swift interop rules learned from CI (Kotlin 2.0.21)

Written down because each one cost a ~20-minute CI round trip:

- A class nested in a class or object is a **nested Swift type**:
  `NodeListModel.Sort`, `ChannelJoin.Outcome`, `Retention.Policy`,
  `Reachability.Verdict`. A class nested in an **interface** stays flat
  (`ConnectionMemoryBle`), and an interface's companion is
  `ConnectionMemoryCompanion.shared`. A class's companion is
  `X.companion`; an object is `X.shared`.
- Don't make Swift name a three-deep sealed subclass; add a small Kotlin
  helper instead (`LinkInbox.openNow`).
- Property or enum names that are C keywords (`short`) or collide in
  Swift (`AdminSession.None` vs `Optional.none`, `ChannelKind.Private`)
  get renamed on export. Expose them from Kotlin under a safe name
  (`ContactNode.reachLabel`, `RepeaterReadout.signedOut`) or compare by
  `.name`.
- **Never spell a Kotlin enum entry in Swift.** `RoutingMode.auto` does
  not exist (`auto` is a C keyword, so the entry is renamed) and
  `RemoteSettingsForm.Kind.multiLine` failed too. Expose Kotlin vals or
  predicates instead (`Routing.MANUAL`, `Field.isSecret`,
  `RegionPanel.isRemove`), and compare with `==`.
- **Big SwiftUI bodies fail to type-check** ("unable to type-check this
  expression in reasonable time"). Split them into small functions and
  give long string expressions their own `let`.
- No default arguments cross over — hence `RepeaterAdmin`, and why the
  engine's `appName` had drifted.
