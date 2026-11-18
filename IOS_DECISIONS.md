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
lands on `main`. Merging is left to the author.

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

13. **2,000 messages kept per thread**, oldest dropped first, and only
    within the same thread so a busy channel never evicts a quiet DM.
    Android's configurable retention policy is not ported yet.

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
