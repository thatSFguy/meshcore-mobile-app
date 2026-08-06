# Changelog

Release notes for MeshCore Hardened, newest first.

This file is the source the GitHub release notes are built from — the release workflow
extracts the section matching the tag. The app carries its own copy of the same text in
`AboutSection.kt` (a changelog behind a link is one you can't read in the field), and a test
holds the two to the same set of versions so neither can quietly drift.

Every entry describes what is in **that tagged build**. A feature that landed after a tag
belongs in the next section, not this one — 0.3.0 was once credited with four features that
shipped after it, which misled nobody so much as the author, three months later.

## 0.6.3

- **Restoring a backup now actually restores contacts.** The restore dialog offered a contacts
  option, counted them, and then never wrote them — admitting it in a sentence at the end of a
  long status line. Restoring onto a new radio left you with no contacts and no obvious reason.
- **Any MeshCore QR can be scanned from either scanner.** Scanning a repeater's contact code
  from the Chats + button used to answer "Invalid community code", because that button was
  wired only to the community parser. Contact, channel, advert and community codes are now
  recognised wherever you scan them — from Chats, from Nodes, and from the Add channel sheet.

## 0.6.2

- The route map painted over the rows around it — "Hops travelled" and the Route heading were
  hidden behind it, and pinch-zooming threw the route lines across the whole sheet. A map view
  draws across everything it is given and does not clip itself; now it does.
- Message info scrolls. The map added about 250dp to a sheet that could not scroll, so on a
  long route the hop list underneath was simply unreachable.
- osmdroid is now configured when the app starts rather than by whichever map happens to be
  built first, so no screen can be the one that forgets.

## 0.6.1

- The route map drew no tiles unless you had opened the Map tab earlier in the same session.
  osmdroid's configuration is process-wide and only the Map tab was setting it; without it
  OpenStreetMap rejects the request and the map shows an empty grid that looks exactly like
  "map tiles are switched off". Both maps now configure it.
- A short route also zoomed past the deepest zoom level that has tiles, producing the same
  empty grid from a different cause. Clamped.

## 0.6.0

- **Message info now draws the route on a map.** Long-press a received message → Info, and the
  path it took is drawn above the hop list.
- Nodes that have never advertised a position are placed *approximately* so the shape of the
  route is visible — a hollow pin with a `?` on a dotted line, never a solid pin. A companion
  node with no GPS is offset away from the first repeater rather than stacked on it; an
  unplaceable repeater mid-route sits between its two neighbours.
- A hop that could not be **identified** — no contact matches its hash, or several do — is
  never placed at any confidence. The line across it is dashed, which is what dashed already
  means on the main map: no route is being claimed here.
- The hop list underneath still names every node, so nothing is lost when the map cannot draw
  one.

## 0.5.7

- Tapping a message notification now opens **that conversation**, instead of just opening the
  app and leaving you to find it. Back from there lands on Chats, wherever you were before.

## 0.5.6

- Reply notifications showed the quote and the answer run together — a reply of "good" to
  "yeah" arrived as ">yeah good". The one line Android shows before you expand is now the
  **reply**; expanding shows the quoted message above it, marked with ↩.

## 0.5.5

- The conversation list showed the message being *replied to* instead of the reply. A reply
  is sent as the quoted text followed by what you actually said, and the preview was taking
  the first 80 characters — so the list read as though everyone were repeating each other.
- Reactions now notify. A thumbs-up is often the whole reply, and it used to arrive in
  complete silence. Only for reactions to **your own** messages — a reaction to someone
  else's message in a busy channel is somebody else's conversation.
- Opening a conversation clears its notification. Previously the notification only
  disappeared if you tapped it, so reading in the app left the phone still insisting there
  was something to read.
- **Send advert (0-hop)** and **Send advert (flood)** are on the Chats menu. Announcing
  yourself is situational — "nobody can see me, say hello again" — and it had ended up three
  taps deep behind a settings page.

## 0.5.4

- Signing in to a repeater now retries if nothing comes back, and waits as long as *the
  radio* says to instead of a flat 20 seconds. The last attempt clears a dead path and
  floods, the same as a message — the firmware routes a login over the stored path exactly
  like a text message, so it fails the same way.
- A **rejected** password is never retried. It won't start working, and the password is on
  the air in cleartext each time.
- "The node rejected that password" and "No answer from the node" are now different
  messages, because they need different fixes.

## 0.5.3

- A direct message that fails twice is now retried as a flood, once — and the dead path is
  cleared first, so your radio can learn a live one from the reply. Before this, all three
  attempts went down the same broken route.
- This is MeshCore's documented default, and like the stock app it can be turned off:
  **Settings → Mesh policies → Flood on the last message retry**.

## 0.5.2

- Command help said "1 commands" when a search matched exactly one.

## 0.5.1

- Tapping a repeater, room or sensor now goes straight to signing in, and signing in leads
  straight to its tools. Checking a repeater's status went from five taps and a scroll to
  three.
- The Status screen asks the node for status when you open it, instead of showing five
  "Fetch" buttons and waiting to be told.
- There is no longer a signed-out version of the admin screen to get stuck on: you sign in,
  or you go back.
- Appearance, notifications, privacy and the diagnostics log are one screen again. Splitting
  them gave four pages holding one switch each.
- Settings rows that need a radio are no longer greyed out — six of the first ten rows looked
  broken before you had done anything.
- Long-press a node for its details (key, position, routing, rename, QR).

## 0.5.0

- Repeater, room and sensor administration is now a hub: one screen per tool (Status,
  Settings, Regions, Identity, Console, Command help) instead of six tabs sharing one screen.
- Signing in to a node is a dialog, and the node's answer — ADMIN or GUEST — is shown on the
  hub. Nothing about the session is guessed from what you typed.
- Settings is a list of pages instead of eleven expandable sections. Each row shows its
  current value, so which transports are on, what frequency the radio is using and whether
  map tiles are being fetched are all answered without opening anything.
- Long explanations are one line with a "More" tap rather than three sentences on every row.
- This release changes how the app is laid out. It has not yet been run against a radio.

## 0.4.0

- "Arrived via": the route a received message actually travelled, hop by hop. Shown only when
  it is known — a flooded message says so instead of guessing.
- Manual routing is now pick-and-order: tap a repeater to add it, arrows to reorder. No more
  typing hex.
- Config backup and restore, with secrets encrypted under a passphrase or left out.
- Message retention and a single purge-local-data action.
- Blocked contacts (by public key) and hidden channel names (by name — a filter, not a block).
- Regional radio presets, and a first-run setup flow.
- Contact permissions, active node discovery, and per-conversation notification levels.
- Repeater neighbour tables and identity-key management.
- Sensor nodes as a first-class type; routes drawn on the map, with gaps where a hop can't be
  placed rather than a guessed line.
- Fixes: unconfigured channels appearing in Chats; path trace sending a request no node would
  answer; "Apply path" silently doing nothing; hop counts that were really byte counts.

## 0.3.0

- Regions: named flood scopes, per-channel scoping, discovery from nearby repeaters.
- Repeater region administration.

## 0.2.x

- Repeater admin: access list, command help, noise floor, clock drift, position picker.
- Messaging: links, quote-replies, reactions, drafts, pinning, nicknames.
- Per-contact telemetry, channel QR sharing, room post authorship.

## 0.1.0

- First release: BLE/USB/TCP transports, direct messages and channels, node map.
