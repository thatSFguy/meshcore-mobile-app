# Changelog

Release notes for MeshCore Hardened, newest first.

This file is the source the GitHub release notes are built from — the release workflow
extracts the section matching the tag. The app carries its own copy of the same text in
`AboutSection.kt` (a changelog behind a link is one you can't read in the field), and a test
holds the two to the same set of versions so neither can quietly drift.

Every entry describes what is in **that tagged build**. A feature that landed after a tag
belongs in the next section, not this one — 0.3.0 was once credited with four features that
shipped after it, which misled nobody so much as the author, three months later.

## 0.7.7

- **Frequency is MHz and bandwidth is kHz everywhere.** The two screens disagreed because
  the two transports do: the companion API takes whole kHz and Hz, the text CLI takes MHz
  and kHz, and each screen showed its own transport's units. The same radio read `910525` /
  `62500` in Settings and `910.5250244` / `62.5` on a repeater one tap away. Both screens
  now read and accept MHz and kHz; the wire is unchanged and conversion happens at the edge.
- **The frequency no longer reads `910.5250244`.** That was not a bug so much as an honest
  rendering of an ugly truth: the node stores frequency as a 32-bit float, `910.525` cannot
  be represented exactly in one, and the CLI prints the nearest value at full precision.
  The app now shortens it — but only when the shorter form is the *same number* to the
  radio, so a frequency genuinely finer than a kHz is still shown exactly as reported.

## 0.7.6

- **A delivered message can no longer be reported as a failure.** The retry loop opened a
  fresh listener for each attempt, and the engine's event stream has no replay — so an ACK
  that arrived when nothing happened to be listening was simply lost. Three ways that
  happened, all of them a message that *did* arrive: during the backoff between attempts;
  for an earlier attempt, since each is sent under its own ACK hash and only the current
  one was watched; and just after the last attempt gave up. One listener now runs for the
  whole send and records what it sees, so asking "did any of mine land?" re-checks what is
  already known instead of racing.
- **A late ACK now corrects the message.** After the last attempt the message still goes
  to Failed — that is the honest report of what is known then — but the app keeps
  listening for 30 seconds, and a reply that arrives late flips it to Delivered. A timeout
  is an estimate, not a deadline the mesh agreed to, and showing a delivered message as
  failed invites you to send it again over a link that worked.

## 0.7.5

- **The "4 B" path-hash option never worked and is gone.** Mode 3 is reserved, and the
  firmware refuses it at every layer — the companion handler answers `ERR_CODE_ILLEGAL_ARG`,
  the CLI answers "Error, must be 0,1, or 2", and the node clamps it to 2 on load. Tapping
  it did nothing and said nothing. The real range is 1–3 bytes, and the in-app command help
  said "0–3" too; both now come from one place that a test holds to the firmware's range.
- **You can set the path hash width on a repeater from the app.** It was the one radio
  parameter that still needed the console. It sits under Settings → Radio on the node you
  are administering, reads the node's current width when you open the section, and applies
  on tap rather than on Save — a chip showing a value the node has not been told about
  would be a lie. Every node on a mesh must match, so it is deliberately next to the
  frequency and bandwidth it has to agree with.
- Noise floor reads **dBm**, not dB — an absolute power, like the RSSI beside it. SNR is
  the ratio and correctly keeps dB. The noise-floor watch had no unit at all.

## 0.7.4

- **A contact's route is drawn where you edit it.** "Show route on map" set a flag the Map
  tab read, then left you in the routing sheet: it did not navigate, its confirmation was
  swallowed by the sheet, and the summary it drew over on the Map tab was painted over by
  the map itself. Tapping it did nothing you could see. The route now renders inline in
  the routing sheet, on the same component the message info sheet already uses — one
  implementation of "draw a route", and it is the one that was already proven.
- The Map tab is the node map and nothing else again; the route flag is gone.
- A stored route whose last hop **is** the destination — routing to a repeater, the
  ordinary case — no longer stacks two pins on one spot. A hop we cannot identify never
  stands in for the destination, however well its hash matches, but one we know the
  identity of and not the position still does.

## 0.7.3

- **Fetch neighbours works.** It never had. The request carried one byte — the request
  type — where the firmware reads eleven, so the node took the "how many to return" count
  from whatever followed our payload, read zero, and answered with a table header and no
  rows. The app reported that as *"knows 2 neighbour(s) but returned none — try again, the
  table may be paged"*, which was wrong twice: it was not paging, and no amount of
  retrying could have helped. The request now carries count, offset, sort order, prefix
  length and a nonce, and pages properly when a node knows more than one reply can hold.
- Neighbours ask for a **6-byte key prefix** instead of 4. 32 bits is cheap to collide on
  purpose, and a colliding prefix puts a name on the wrong node.
- Each neighbour shows **how long ago it was heard**. That field was being read as an
  epoch timestamp; the firmware sends elapsed seconds, on the node's own clock.
- The section now says **what a neighbour table actually is**: other repeaters, heard
  directly at zero hops. Companions, rooms and sensors never appear, and a relayed advert
  never counts — which is why a healthy repeater reports two or three and not a cap.
  Nodes that keep no such table no longer offer the button at all.
- **New: Probe.** The table is filled only by adverts that happen to arrive — nothing
  polls it and nothing expires — so it lists who advertised since the node booted, not
  who is in range. Probe broadcasts `discover.neighbors` and makes them answer. On a live
  repeater this turned up a neighbour at a perfectly usable 3.5 dB that had been absent
  for hours. It costs airtime, so it is a deliberate button and not something the plain
  fetch does behind your back; admin only, since it goes through the node's CLI.
- **The admin console shows console traffic only.** It had been rendering the whole
  message thread with the node, so on a room server the room's own chat — "hey", "hi",
  "check" — appeared among the CLI replies, and **"Clear console" deleted those messages
  along with the command history**. Clearing now takes the console and nothing else.
- **The console is ordered by when things arrived here**, not by the timestamp the node
  claims. Repeaters and room servers have no GPS and usually no correct clock, so a reply
  could sort hours away from the command that caused it.

## 0.7.2

- **Direct-message repeats actually work now.** 0.7.1 shipped them looking correct and
  doing nothing: a repeat was credited only when exactly one message to that contact
  existed in a two-minute window, so sending two or three messages to one person — what
  trying the feature looks like — discarded every result. Correlation is now on echo
  timing, which is what genuinely separates two messages to the same recipient.
- **Confirmed on a live mesh**, not just in tests: a direct message re-broadcast by two
  nodes, both copies heard, both credited to the right message. Those exact bytes are
  pinned in the test suite.
- A message can now read `✗ (try 3) · ↻ 2` — **failed, but two nodes carried it**. That
  distinguishes "the mesh moved it and nobody answered" from "it never left the radio",
  which the delivery tick alone cannot.
- The bubble badge is the glyph and the count, nothing else. The footer already carries a
  time, a tick and sometimes an attempt count; the word was using more of that line than
  the number. The info sheet still names every node in full.
- Debug builds tee the engine log to Logcat, and the repeat log — which names contact key
  prefixes — is now confined to them. Contact keys belong in the encrypted database and
  the Keystore, not in a release build's system log.

## 0.7.1

- **Repeats now show on the sent message itself** — a badge under the bubble,
  which is where you're looking when the question occurs to you. The standing
  "Who repeats me" screen stays; it answers the coverage question this one can't.
- **The info sheet names them.** Long-press a sent message → *Message details* → **Repeated
  by** lists each node, resolved to a contact name where exactly one matches and left as a
  hash where more than one does.
- Channel posts are **exact** — the app decrypts the echo and matches it to the row in its
  own outbox. Direct messages are **correlated**: a rebroadcast DM is encrypted to its
  recipient and exposes one byte of it, so a repeat is credited only when exactly one sent
  message fits, and refuses when two do.
- No repeats heard shows **nothing**, never "0". A node that carried your message onward
  without a copy coming back can't be heard here, so silence is unmeasured, not zero.
- Wording corrected throughout: **node**, not repeater. The first live measurement was
  relayed by a room server, and companions with client-repeat relay too.

## 0.7.0

- **New: "Who repeats me"** (Nodes → ⋮). Which repeaters are actually carrying your
  traffic — the mirror of a message's "Arrived via", and the one you can *run*: tap **Send
  a flood advert** and watch which repeaters send a copy back.
- Each row separates the two things people conflate. A repeater that **heard you** pulled
  your transmission out of the air; a repeater that **you heard** is the one whose
  transmission reached your radio. Only the second has a measured SNR, and only that one
  shows a number. One repeater doing both is flagged **Two-way**.
- Nobody else can put a repeater on your list. Every row comes from a copy of your own
  **Ed25519-signed advert**, so forging one would need your private key.
- The screen says what it cannot see: a repeater that carried your traffic onward without
  a copy coming back cannot appear, so the list is a floor, not a coverage map. An
  ambiguous hop stays `(2 matches)` rather than being credited to one repeater.
- Under the hood this was thought impossible here for five days — our own notes said the
  route data "isn't answerable from the RX log alone". Reading the firmware settled it: the
  radio hands the app every packet it demodulates *before* discarding duplicates, and a
  repeater bouncing your own packet back is exactly such a duplicate.

## 0.6.7

- **The app's "Forget" is now "Remove", because it was being confused with Android's.**
  Removing a saved node drops this app's list entry; it does *not* touch the Bluetooth
  pairing. Only Android's own "Forget device" does that, and until you use it the phone
  reconnects on the old pairing — so a changed PIN looks like it did nothing.
- After changing the PIN and rebooting, the app now says this plainly and offers to open
  Bluetooth settings, which is where the pairing actually lives.

## 0.6.6

- **Forget now actually forgets.** It removed a radio from Saved nodes but left it in the
  auto-reconnect memory, so the node came straight back on the next connect — and because
  that path never re-added it to the list, you ended up connected to a radio the app no
  longer listed. Forget clears both, and disconnects if it is the one you are attached to.
- Any successful connection now appears in Saved nodes, including automatic reconnects. The
  list was only written when you picked a radio by hand.

## 0.6.5

- **Fixes the PIN screen shipped in 0.6.4**, which got three things wrong. It accepted PINs
  the radio refuses (anything starting with 0 — the firmware takes 100000–999999, or 0), it
  claimed the current PIN could not be read when the node reports it in every DEVICE_INFO
  frame, and it never mentioned that **the change only takes effect after a reboot**.
- The screen now shows the configured PIN, refuses what the radio would refuse, explains that
  `000000` clears the PIN rather than setting one, and offers to reboot straight after.

## 0.6.4

- **Change the radio's Bluetooth pairing PIN** — Settings → Radio link → Bluetooth PIN. Nodes
  without a screen ship with 123456, which is public and the same on every one of them, so
  until you change it anyone in Bluetooth range can pair and read the radio's contacts,
  messages and settings.
- The current PIN is never shown, because the firmware provides no way to read it back. The
  screen can only set a new one.
- Changing it invalidates the pairing your phone already has, so it says so before you commit
  and tells you to write the PIN down — nothing can recover it.

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
