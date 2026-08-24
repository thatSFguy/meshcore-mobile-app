# What MeshCore Hardened does

The complete feature list. [`README.md`](README.md) summarises; this is the inventory.

Two documents sit either side of it. [`PARITY.md`](PARITY.md) compares this surface against the
official MeshCore Android app and records what is deliberately missing and why.
[`CHANGELOG.md`](CHANGELOG.md) says which release each thing arrived in.

**A note on the caveats below.** Several entries say what a feature does *not* prove. That is not
hedging for its own sake — a mesh client that overstates what it knows sends you to fix the wrong
problem, so where this app cannot know something it says so, and those limits are part of the
feature rather than a footnote to it.

---

## Transports and connection

- **BLE** (Nordic UART Service), **USB serial** (CDC-ACM / CP210x) and **TCP**, each with its own
  enable toggle. A disabled transport is never started, so it never scans, connects, or feeds bytes
  to a parser.
- **TCP is off by default**, behind a one-time stern warning: the MeshCore TCP link is unencrypted
  and unauthenticated, and the UI keeps flagging it for as long as it is connected.
- Saved-node list; **automatic reconnect** with backoff; a **foreground service** so the radio link
  survives backgrounding.
- Bluetooth PIN entry for radios that pair with one.
- Live connection state in the top bar of every screen — radio name, or why there isn't one.

## Messaging

- **Direct messages** and **channel messages**, with paged scrollback.
- **Automatic retry on the firmware's documented terms**: three attempts, each waiting the radio's
  own airtime-derived ACK timeout, with the last attempt clearing a dead path and flooding so the
  radio can learn a live route from the reply. On by default, and switchable off.
- **Delivery ticks come only from real end-to-end ACKs.** Nothing is inferred from a send
  succeeding.
- Long-press a message for copy, quote, and **message details** — SNR, attempts, ack hash, hop
  count.
- **Reactions.** MeshCore has no reaction field, so every client invents a text convention. This
  app reads both conventions on the air and sends the one this mesh mostly runs; an unmatched
  reaction renders as "reacted to an earlier message" rather than as raw wire text. Formats and the
  reasoning: [`MESHCORE_PROTOCOL.md` §14](MESHCORE_PROTOCOL.md).
- Mark-unread, per-thread mute, quoting, automatic link detection.
- **Arrived via** — the route a received message actually travelled, in travel order, drawn on a
  map. A channel message's route is exact; a direct message's is correlated, and is claimed only
  when exactly one packet fits. A hop that cannot be identified stays a gap.
- **Repeats** — a `↻ 2` badge on a message you sent, naming the nodes heard re-broadcasting *that
  message*. Paired with the delivery tick it separates "the mesh moved it and nobody answered" from
  "it never left your radio".

## Channels

- 16-byte PSKs, `#hashtag` key derivation, private channels with generated keys.
- **Community QR join** — the community secret is sealed in the Keystore and its channels derived
  from it.
- **Channel senders** — the names seen posting on a channel, presented as appearances rather than
  membership, because a channel message carries no sender key.
- Presented as **obfuscated, not secure** on every surface: the protocol mandates AES-ECB with a
  2-byte MAC, and the UI says so rather than implying privacy it cannot provide.

## Nodes and contacts

- **Contacts / Repeaters / Rooms / Sensors** tabs, plus a **New** tab holding a discovery inbox of
  signature-verified adverts you have not added yet.
- Search; sort by recent activity, last heard, name or fewest hops; filter to favourites, unread,
  or heard in the last 24 hours.
- Favourites, rename, private nicknames, hash-coloured avatars, per-contact permissions.
- **QR share and import** interoperating with the official app's `meshcore://contact/add?…` form,
  with the older signed-advert form still accepted on scan. A contact card is **unsigned**, so the
  app shows the full public key and asks before adding.
- **Discover nearby repeaters** — a broadcast asking nearby nodes to speak up.
- **Who repeats me** — which nodes carry *your* traffic. Every row is a copy of your own
  Ed25519-signed advert, so nobody can add themselves to it. It distinguishes a node that heard you
  from one you heard, and says plainly that it is a floor rather than a coverage map.
- **Remove stale nodes** — a 3-to-30-day slider that prunes the radio's contact list. **Favourites
  are never removed**, nor is a node never heard from at all, nor one you have exchanged a message
  with inside the window. The list is shown before the button is pressed.
- **"Last heard" means when your radio heard the node**, not the timestamp the node put in its own
  advert. A node whose clock disagrees with what was observed is named as such rather than silently
  corrected.

## Routing

- Per-contact **Auto / Flood / Manual** routing.
- A **hop-by-hop path editor**: tap a known repeater to append it, reorder, remove. Hops carry the
  node's full public key and derive their hash at the mesh's current width, so the route cannot be
  pinned at the wrong width. Free-text hex survives behind a toggle for routes copied from
  elsewhere.
- **Path history** — every route seen or used, with success/failure quality labels, capped per
  contact, with the flood route pinned so it is never pruned.
- **Path trace** — each hop with its SNR.
- A hop is named only when **exactly one** contact matches its truncated hash; otherwise it stays
  `(N matches)`. Never a silent pick.

## Map

- Every node advertising GPS, with type-specific markers and always-visible name labels.
- Filter by node type, fit-all-nodes, **GPX export**, tile-cache clearing, and tile fetching
  switchable off entirely — the map then plots markers on a blank canvas and nothing leaves the
  device.
- **Tap a node** for a popup: what it is, when it was last heard, and its tools.
- **Neighbour links** — for a repeater, lines drawn to every neighbour the app can place, coloured
  and weighted by the signal that repeater reported, with the quality written on the line
  ("Strong · 12.0 dB"). A line is drawn only where exactly one known node matches the neighbour's
  key prefix *and* has a position; ambiguous, unknown and position-less rows are listed with the
  reason instead. The table is kept with the clock reading that produced it, so it can say how old
  it is.

## Repeater and room administration

- A **hub** with one screen per tool. You sign in with a password (sealed in the Keystore if you
  ask); the node decides what that unlocks and reports it, and the hub shows what you got — `ADMIN`
  or `GUEST`. There is deliberately no access-level control to set, because there is no such choice
  to make.
- **Status** — battery, uptime, queue depth, RSSI/SNR, airtime, packet and duplicate counts,
  channel utilisation — decoded into fields rather than raw CLI text.
- **Telemetry** (Cayenne LPP), **noise floor** watching, **neighbours**, and the **access list**.
- **Regions**, **identity** and rekeying, and a form-based **settings editor** that fetches live
  values over the CLI and saves only what you changed.
- A raw **console**, and a **command-help** catalogue filtered by node role *and* session role — a
  guest is never offered a command the node would refuse.
- **Waits sized by the radio.** A request's timeout comes from the radio's own airtime-and-hop
  estimate rather than a fixed number, and the spinner reports what is actually known: how the
  request went out, when the answer is expected, and when that estimate has passed.

## Firmware updates over Bluetooth

For nRF52 radios on companion firmware v1.15 or newer. Settings → Firmware updates the radio you
are connected to; the repeater hub has the same tile for a node you can stand next to, since
`start ota` crosses the mesh but the image cannot.

- Nordic **legacy DFU** spoken in-app — the transfer itself, not a hand-off to another tool.
- Builds fetched from the MeshCore GitHub releases and checked against the published checksum, or
  opened from storage if you would rather download them yourself.
- **The board is confirmed by name before anything is written**, because a DFU package cannot say
  which board it is for — every nRF52 board declares the same device type.
- A recovery path for a node left in update mode, including the Bluetooth address it announced.
- ESP32 boards are told plainly that their path is USB or their own WiFi hotspot.

## Device settings

A hub of grouped pages covering the companion-command surface: connection, transports, identity
(name, position, advert interval, QR), radio parameters, clock drift, mesh policies (advert
location, multi-acks, telemetry permissions, path-hash width, flood scope), auto-add policy, custom
variables, channels, blocked senders, Bluetooth PIN and firmware.

Every tile carries a **live subtitle** — which transports are on, what frequency the radio is
using, whether anything is blocked — so the state is answered without opening anything.

**Joining a mesh by QR**: a settings code carries frequency, bandwidth, spreading factor, coding
rate, path-hash width and an optional flood region, and deliberately **not** transmit power or
channel keys. Scanning one shows what it will change and asks first.

## App settings, privacy and data

- Appearance; **notifications** with per-kind switches and per-thread mute.
- **Retention** — how long messages are kept — and **purge**, which clears local data.
- **Backup and restore** of configuration, secret-safe.
- A **diagnostics log that is off by default** and redacts `set prv.key`, passwords and long hex
  blobs before a line is stored.
- Blocked senders, by full 32-byte public key for direct messages. Channel "blocking" is impossible
  — a channel message carries no sender key — so that ships as **Hidden channel names**, labelled
  as the noise filter it is.

## Notifications

Inbound direct and channel messages, with per-kind switches and per-thread mute. A **reply** shows
the reply first and the quoted message behind the expander. A **reaction to your own message**
notifies — a thumbs-up is often the whole reply — while reactions to other people's messages stay
quiet. Opening a conversation clears its notification.

## Security properties

These run through everything above rather than being a feature of their own; the reasoning is in
[`README.md`](README.md#security-posture) and [`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md) §12.

- **Advert Ed25519 signatures verified** before any node is imported, mapped or shown in the
  discovery inbox.
- **Channel sender names never trusted** for identity, contact mutation or echo suppression.
- **Secrets in the Android Keystore** (AES-GCM, key in the TEE/StrongBox) — login passwords,
  channel PSKs, community secrets, identity seed. If the Keystore is unavailable the app declines
  to store them rather than falling back to plaintext.
- **The message database is encrypted** (SQLCipher), and Auto Backup is disabled so history and
  identity never reach a cloud backup.
- **Every frame parse is bounds-checked** — truncated or hostile frames degrade to "unknown frame"
  instead of crashing the RX path.
- **Two outbound connections, both of which you trigger**: OpenStreetMap tiles, and firmware
  downloads from GitHub.
