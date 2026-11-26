# Changelog — MeshCore Hardened for iOS

Release notes for the iOS app, newest first. The iOS release workflow
(`.github/workflows/ios-release.yml`) builds the release notes from the
section matching the tag (`ios-v0.1.0` → `## 0.1.0`), and fails without one.
Android's notes are in the repository's top-level `CHANGELOG.md`.

Every entry describes what is in **that tagged build**.

## 0.2.1

**Fixes**
- **Message privately: a lost acceptance could not be retried.** An acceptance is a channel
  post, and channel posts can be lost. When one was, the other person's radio never added you,
  and asking again did nothing, because the request read as already answered. Asking again now
  brings back Accept and Decline. Accepting again re-sends the acceptance without re-adding the
  contact, which would have reset the route and name the radio had learned for them. (The fix
  is in the shared code, so Android 0.11.4 has it too.)

## 0.2.0

Parity with the Android app. Everything Android does is now on iOS, apart from
firmware updates and repeater identity rekey, which stay Android-only for now.
Most of it is the same shared code Android runs, and it is tested, but much of
it has not yet been driven against a radio on an iPhone — rooms, setup, links,
remote presets and the repeater auto-add rules most of all.

**Install.** Unsigned, as before: re-sign with your own Apple ID (a free one
lasts 7 days; re-sign and reinstall after that — your messages are kept).

**Messaging**
- Room servers: tap a room to sign in, then read and post. Posts show who wrote
  them, a banner says when your session can't post, and an admin gets Manage room.
- Links in messages are tappable. A web link asks first, because opening it
  leaves the mesh and shows the site your address; a `meshcore://` link goes
  through the same confirmation as a scanned code.
- The composer stops at the radio's size limit, quote included, with a counter
  near the end. Before, iOS would send a message too long for the radio.
- A thread menu: mute notifications, mark unread, Names seen (who has posted, to
  hide a name), contact details and routing, clear thread. Drafts are kept.
- Chats: search, pin a thread to the top, start a message to a contact, paste a
  code from the clipboard, join the Public channel in one tap, and your own QR.

**Nodes, map and routing**
- Favourites, nicknames, ping, and rename a contact on the radio.
- Distances from your radio's own position, and a Nearest sort.
- A node's stored path hop by hop, its hash size, and a warning when its clock
  is wrong.
- Telemetry from people and sensors without signing in; who may read yours.
- Heard nodes not in your contacts: an inbox with a detail sheet, Add (with a
  warning for a far node) and Dismiss. Discover nodes lists who answered.
- The app's own repeater auto-add rules, for when the radio adds none itself.
- Trace places each hop, or says why it can't, and Use traced path fills the
  route. Pick your position on a map.

**Repeaters**
- Use a regional preset on a remote node (it applies on the node's next
  restart, and the app says so), Request status, a console that keeps its lines
  and can be cleared, and command help that fills the console in.
- Sensors open the same sign-in and admin screens as repeaters.

**Settings**
- First-run setup checklist, units and theme, saved radios to switch between,
  a Bluetooth switch for TCP-only setups, Purge local data, What's new, and the
  channel datagram developer tool. Community codes, with the secret in the
  Keychain. Channels can be renamed or rekeyed and shared as a QR.
- A bar at the top of every tab when you're not connected or on unencrypted TCP.

**Removed:** Export nodes as GPX. No one had a use for it, and it wrote every
node's position to a file that left the app.

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
