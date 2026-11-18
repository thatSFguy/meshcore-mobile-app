# Changelog — MeshCore Hardened for iOS

Release notes for the iOS app, newest first. The iOS release workflow
(`.github/workflows/ios-release.yml`) builds the release notes from the
section matching the tag (`ios-v0.1.0` → `## 0.1.0`), and fails without one.
Android's notes are in the repository's top-level `CHANGELOG.md`.

Every entry describes what is in **that tagged build**.

## 0.1.0

The first iOS release. It talks to the same radios as the Android app, follows
its rules (most of them are literally the same code, shared between the two),
and lays its screens out the same way.

**Install.** The build is unsigned: re-sign it with your own Apple ID using
Sideloadly or AltStore. A free Apple ID works, but its signature lasts 7 days;
after that, re-sign and reinstall — your messages are kept. Turn on Developer
Mode (Settings → Privacy & Security) and trust your Apple ID under Settings →
General → VPN & Device Management.

**Messaging**
- Direct messages with the three-attempt retry and flood fallback, and honest
  delivery marks: one tick when the radio took it, delivered only on the
  recipient's acknowledgement.
- Channels: join a hashtag, create or join a private channel, join from a
  `meshcore://` link or QR code, share, leave. Each channel can carry its own
  region. Channels are obfuscated, not secure, and the app says so.
- Swipe to reply (with a short quote), `@` mentions, reactions, resend a
  failed message, and "Message privately" — a request on a channel that,
  once accepted, lets two people send direct messages.
- Message info: how a message arrived (which repeaters, on a small map) or
  who was heard repeating yours.
- History stays on the phone, encrypted by iOS and excluded from backups,
  with retention judged by arrival time, never the sender's clock.

**Nodes and map**
- Contacts, repeaters and rooms with search, sort, filters and whether each is
  two-way, heard only, or unknown.
- Routing per contact: automatic, flood or a manual route picked from
  repeaters; the route a reply would take; past routes with their delivery
  record; trace; and "Check if they can hear you".
- Map with your own position, node types, labels, filters, neighbour links
  and GPX export. Uses Apple's map tiles.
- "Who repeats me", and removing stale nodes from the radio.

**Your radio** (Settings, laid out as on Android)
- Connection with automatic reconnect, transports (TCP behind a warning),
  Bluetooth PIN, name and position, adverts, radio parameters with regional
  presets and settings QR codes in and out, clock, mesh policies with flood
  scope and regions, auto-add, custom variables.
- Backup and restore, including channel keys under a passphrase (AES-256-GCM).
  Files are interchangeable with the Android app.
- Simple mode, notifications, and an off-by-default diagnostics log that can
  be saved for a computer to read.

**Repeaters and rooms**
- Sign in, status, telemetry, noise-floor watch, neighbours, access list, the
  console and command help; remote settings (validated before sending),
  regions including untagged traffic, maintenance commands, and applying a
  settings QR to the node.

**Not in this release**
- Firmware updates and changing a repeater's identity key (deliberately left
  out for now).
- Favourites, nicknames, ping, the discovered-nodes inbox, saved radios and
  community codes — coming next.

**Tested how far.** The app has been run on an iPhone against real radios:
connecting, messaging, reactions, and a repeater's sign-in, status, settings,
regions, neighbours, telemetry, access list, routing and reachability check.
Backup and restore, manual routes, settings codes, per-channel regions, the
stale-node sweep and "Message privately" are covered by tests but have not yet
been tried on hardware from iOS.
