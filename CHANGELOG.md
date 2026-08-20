# Changelog

Release notes for MeshCore Hardened, newest first.

This file is the source the GitHub release notes are built from — the release workflow
extracts the section matching the tag. The app carries its own copy of the same text in
`AboutSection.kt` (a changelog behind a link is one you can't read in the field), and a test
holds the two to the same set of versions so neither can quietly drift.

Every entry describes what is in **that tagged build**. A feature that landed after a tag
belongs in the next section, not this one — 0.3.0 was once credited with four features that
shipped after it, which misled nobody so much as the author, three months later.

## 0.8.2

**The node list can be ordered and narrowed.** A mesh accumulates nodes — every repeater
whose advert reaches the radio becomes a row — and the list had exactly one order and no way
to hide anything. There is now a menu beside the search box.

- **Sort by recent activity, last heard, name, or fewest hops.** "Fewest hops" is a
  reachability order rather than a distance one: it answers which nodes can be reached
  cheaply right now, which is the question worth asking before sending anything large.
  Flood-routed nodes come last, because that is what having no path means.
- **Show only favourites, only nodes with unread messages, or only nodes heard in the last
  24 hours.** They stack. The last one is the answer to a list full of nodes that were heard
  once, months ago, and never again.
- **The order and the filters are remembered**, like the selected tab already was. A list you
  had to arrange once should not need arranging again.
- **An active filter says so.** The icon changes colour, the filters are named under the
  search box, and a list emptied by a filter says which filter emptied it and where to turn
  it off — an empty list is otherwise indistinguishable from the app having lost your
  contacts.

**A node's row says "14 min ago" instead of a date.** A date makes the reader do the
subtraction, and every node heard today rendered as the same date, which told you nothing at
all. The detail sheet still carries the exact timestamp alongside the age, for when the
precise moment is what you want. Ages also read "1 hour ago" rather than "1 hours ago", which
was the wording for the whole first hour after every advert.

## 0.8.1

**A radio that goes out of range now comes back on its own.** Walk out of Bluetooth range and
back, and the app reconnected only by luck. Three things were wrong at once, and each of them
alone was enough to leave a radio sitting on the table, in range, disconnected.

- **A connect attempt could hang for ever.** The sequence parked on Android callbacks that a
  dropped link never delivers — the MTU exchange and the notification subscription each waited
  on a success that was no longer coming — so an attempt begun while the radio was walking
  away never finished and never failed, and the reconnect loop sat behind it indefinitely.
  Every step of the connection is now registered in one place and released together when the
  link drops, so no step can be forgotten, and the attempt has an outright deadline behind
  that.
- **Every retry was a *direct* connect**, which only samples the instant it happens to fire —
  so reconnecting meant one of those attempts landing in the same second the radio came back.
  After the first try the app now hands the waiting to the Bluetooth controller, which
  completes the link by itself the moment the radio advertises again, minutes or hours later.
- **The backoff measured from when an attempt started, not from when the link came up**, so
  forty seconds of failing to connect counted as forty seconds of a healthy connection. The
  interval kept resetting and the app hammered an absent radio at full speed instead of
  easing off.

The Bluetooth handle is now always released as well. Android hands out a limited number of
them, and a transport rebuilt on every retry could exhaust them over an afternoon of a radio
coming and going — after which nothing would connect until Bluetooth was switched off and on.
The link also writes to the diagnostics log now, with radio addresses trimmed to their last
two octets so a log stays shareable and two radios stay apart.

## 0.8.0

**Your channels can no longer be emptied by an unrelated error.** A refusal from the radio —
one belonging to some other request entirely, seen in the field right after a flood advert —
could arrive while the app was reading the channel slots, and was taken to mean "this radio
has no channels". Every channel vanished until the app was restarted; threads that had
messages showed as "Channel 0" and "Channel 1", and the rest were simply not there. Nothing
was actually lost, but the next slot the app would have handed a new channel was slot 0 —
the one holding Public, whose key the radio cannot give back. The radio cannot mean that: it
answers for every slot it has, configured or not, and only refuses an index past the end. So
a refusal where the radio has no such answer to give now keeps the list the app already had.

**Firmware updates over Bluetooth.** An nRF52 radio can be updated from inside the app —
pick a build, confirm the board, watch it flash — instead of running `start ota` here and
finishing the job in Nordic's DFU app with the right packet-receipt settings.

- **Update the connected radio.** Settings → Firmware. The app asks the radio to reboot
  into its bootloader, finds it again under its update-mode name, and sends the image. Needs
  an nRF52 board on companion firmware v1.15 or newer — that is when MeshCore started
  exposing the DFU service — and the screen says so plainly when the radio does not have it,
  rather than leading you into a flow that can only end in "not supported".
- **Update a repeater or room server.** The repeater hub gets a Firmware tile (admin only).
  `start ota` goes over the mesh; the firmware itself does not, and cannot — you have to be
  within Bluetooth range of the node to finish. That is stated before the button, not after.
  The command is also gentler than it sounds: the node switches its Bluetooth on and carries
  on repeating, and only reboots into its bootloader when something connects and starts the
  transfer. The address it reports back is used to pick it out from anything else nearby
  that is also advertising for an update.
- **The radio now reports what it is running.** Board name, firmware version and build date
  were always in the `DEVICE_INFO` frame and were being skipped. They are on the connection
  screen and drive the Firmware row's subtitle.
- **Firmware can be fetched in the app or opened from storage.** "Check for firmware" reads
  the MeshCore release list from GitHub — the second outbound request this app makes, after
  map tiles, and only when you ask for it. Downloads are checked against the SHA-256 the
  release published and refused outright on a mismatch; a package opened from storage is
  hashed and shown as unverified, because it is.
- **The board is confirmed by name before anything is written.** A DFU package cannot tell
  you which board it is for — every nRF52 board declares the same device type — so the
  filename is the only evidence and a human reads it. Where a board ships more than one
  build (T114 with and without a display), both are offered and neither is chosen.
- **ESP32 boards are told the truth.** Their over-the-air path is a WiFi hotspot and a
  browser upload. This app does not use WiFi and does not pretend to offer it.
- **You choose the version, not just "the latest".** MeshCore releases often and a release
  is sometimes withdrawn or quickly followed by a fix, so the version worth putting on a node
  you have to climb to is usually one already running somewhere you can reach. Every
  published version is listed, the installed one is marked, and going backwards is allowed —
  the bootloader does not check that a version is newer.
- **A node that is stuck can be recovered from the node list.** Long-press it: the address it
  announced when it entered update mode is remembered, so it can be told to restart (DFU
  system reset, which boots the firmware it still has) or flashed again — neither of which
  needs the mesh, which is exactly what a node in update mode has left.
- **A transfer is refused over a link too weak to finish it.** The node erases its firmware
  before writing the replacement, so a transfer that stops half way costs a visit. Below
  −95 dBm the app says so and offers to try anyway rather than starting and hoping. The
  floor is deliberately close to the noise floor: nodes on masts cannot be approached, and a
  guard that refuses every attempt on the ones that most need updating is not a safety
  feature.
- **A node left part-way through an update is now unstuck automatically.** The bootloader
  keeps the state of an interrupted transfer until it restarts — a disconnection does not
  clear it — so one attempt that got as far as the start step made every attempt afterwards
  fail with "invalid state", including the "retry more slowly" this app itself recommends.
  Worse, it was reported as the node refusing the package, which sends you off to re-check a
  file that was never the problem. Every abandoned transfer now resets the node on its way
  out, and a node found already stuck is reset, waited for, and flashed.
- **"Restart it" no longer reports a successful restart as a failure.** The node reboots
  while handling the write, so the write can never be acknowledged; that was being read as
  a refusal, exactly as the reboot-into-bootloader write once was.
- **A node's update-mode address is only recorded when it is the node's own.** A bootloader
  advertises on that address plus one, so recording *its* address made every later search
  look one address too high — and made the app offer the reboot request to a node already
  in its bootloader, which reads it as a malformed start command.
- **Update mode is now a flag of its own, not a guess made from a node's Bluetooth address.**
  A node's BLE address, board and firmware version are properties of the hardware: the app
  learns them when it can and keeps them, because the moment they are needed — choosing a
  build for a node sitting in its bootloader — is the moment the node can no longer be asked.
  "Is in update mode" is not that kind of thing, and it was being read off whether an address
  had ever been recorded. Nothing cleared the address, because nothing should, so a node that
  entered update mode once was described as being in it for ever: after a reboot, after a
  finished update, and after being reflashed over USB and put back into service. The screen
  then hid "Send `start ota`", which was the button that would have helped. There is now a
  stored flag with named transitions — set when the node answers `start ota`, cleared when a
  transfer to it finishes and when it accepts a restart out of its bootloader — and a
  node reflashed over USB, which nothing on this side can see, can be corrected with one tap.
  The address is untouched by all of it.
- **The update link now asks for the fastest connection interval it can get.** Android's
  default is 30–50 ms, and at that spacing the Bluetooth stack packs several packets into
  each connection event — so the bootloader receives a whole receipt window in two or three
  bursts, faster than it can move them into flash, and answers "operation failed" a few
  hundred bytes in. Nordic's own updater requests the short interval and so does
  Meshtastic's; this app did not, and a live ProMicro managed 200 bytes in 17 seconds before
  failing.
- **A node that cannot keep up is retried more slowly by itself.** "Operation failed" during
  the image step is the one failure with a documented remedy — send fewer packets between
  acknowledgements — so the app now does that rather than printing advice. Each attempt
  still hands the node back able to start another, and it is tried once, not forever.
- **A finished update is no longer reported as a failure on its last write.** The node
  reboots while handling activate-and-reset, so that write can never be acknowledged — the
  third time this codebase has read a node obeying as a node refusing, after the jump into
  the bootloader and "restart it". All three are now one rule in one place.
- **Packets are sized from the link instead of being fixed at 20 bytes.** The size comes
  from the negotiated MTU, capped at 244 and floored to a whole word (the bootloader rejects
  anything else) — twelve times the throughput on the same link. The Adafruit bootloader
  negotiates properly and rounds its own reply to a whole word, so 20 bytes a packet is not
  what a stock one gives you; it is what a link whose MTU exchange did not happen gives you,
  and the log now says which of the two is in front of you.
- **"Retry more slowly" was a dead button.** It was offered on the failure screen and did
  nothing at all: the retry read the package out of the state it had just been replaced by.
  Nothing appeared in the log, because nothing ran — at the one moment the node is sitting
  with its firmware already erased.
- **A node is asked for its version before it is asked to enter update mode.** `start ota`
  is not a question — the node switches its Bluetooth on and there is no taking it back
  without walking to it — so sending it to a node that has stopped listening does not fail,
  it just leaves the app believing something it has no evidence for. `ver` goes first
  instead: a round trip that costs nothing if it goes unanswered, whose reply proves the
  firmware is running, and which records the version at the last moment anything can still
  ask for it. Only on that answer is `start ota` sent, and only the node's own
  `OK - mac: …` reply puts it into update mode. If it never answers, nothing was sent and
  the screen says so.
- **Signing in to a node takes it out of update mode.** A node in its bootloader has no LoRa
  stack at all, so any reply to a login — including a rejected password — proves the
  firmware is running. It was the cheapest evidence available and the app was ignoring it,
  which is how a node reflashed over USB and signed straight back into was still being
  described as advertising for an update.
- **A node's own `start ota` reply is read once, not for ever.** The admin console is
  stored, so `OK - mac: …` is a row that never goes away and was being re-read as a
  present-tense fact on every visit — the same mistake as the address, one table along, and
  just as immune to correction. Each reply is now consumed once, against a watermark, and a
  correction cannot be undone by history.
- **A node's firmware version is no longer filled in by an unrelated reply.** `OK - mac: …`
  was refused as a board name and accepted as a version — only half the guard was there — so
  a repeater read as "ProMicro DIY · OK - mac: FF:5C:EF:28:2A:92", and that string was stored
  against the contact and compared against release tags. It is reachable from the app's own
  update sequence rather than anything unusual: `ver` is sent, then `start ota`, and if the
  first goes unanswered the second's reply satisfies it.
- **A node's board and firmware version are no longer swapped.** The Firmware screen asked
  a node `board` and `ver` at the same moment, as two separate jobs, and their console rows
  could be written in the opposite order to the sends — so each answer was filed under the
  other command. A live repeater read as "v1.15.0-dee3e26 (Build: 19-Apr-2026) · ProMicro
  DIY", and the version was stored against the contact as its board name, which is what the
  firmware picker narrows on and what the search for a node in update mode matches names
  against. The two commands now go one at a time, an answer of the wrong shape is refused,
  and board names already stored as versions are cleared so they get asked again.
- **The board name reaches the flash step for a node in update mode.** It was passed as
  nothing on that one path, which is the path where it matters most: the node is off the
  mesh and cannot be asked, so the scanner had no name to match on and the version picker
  offered every board's build instead of the one.
- **Packet flow control follows the MeshCore FAQ's own per-board figures.** Eight packets
  between receipt notifications on a T114, ten elsewhere — the setting §7.1 tells you to
  change by hand in Nordic's DFU app before every flash, applied here from the board the
  node reported. Where the FAQ names a board that has an OTAFIX bootloader, the screen
  names it too, with the link, since it can only be installed over USB and so is only
  useful advice before the node goes up.
- **The answer to the start step can no longer be missed.** The radio replies while the app
  is still sending the writes that provoked it, and the reply was being dropped when it
  arrived a moment early — which showed up as the node going silent part-way through.
- **The packets are paced, and that is what made an over-the-air update finish.** The
  bootloader takes each packet into a small buffer and flushes it to flash in the background;
  when that buffer fills it answers "operation failed" and the transfer is over. Its receipt
  notifications cannot prevent it, because one says a packet was *received*, not that it
  reached flash — so the backlog grows across batches however small the batch is. This app
  sent as fast as the Bluetooth stack would take it, about 150 packets a second, and a live
  ProMicro refused the image step at 5 KB, 15 KB and 15 KB again; halving the receipt
  interval, which is what the reference implementation does on a retry, changed nothing
  because it does not change the rate. A 20 ms pause between packets does, and the pause is
  derived from the link rather than a board name: a peer still at the default 23-byte MTU is
  a stock bootloader with small buffers, one that negotiated to 244 is not and gets no pause.
  **A 404 KB image now transfers in about eight minutes and boots** — first proven end to
  end on 2026-08-14, flashing v1.16.0 onto a repeater running v1.17.0 and reading the new
  version back from the node.
- **A node whose firmware has already been erased is never restarted.** Abandoning a
  transfer used to send the node a system reset, so the bootloader would forget the
  half-finished session and accept a new one. That is right up until the moment the start
  step is accepted — because that is when the node erases its application, and a bootloader
  restarted with no application to boot comes back in **USB mass-storage mode and stops
  advertising over Bluetooth altogether**. The reset written to rescue the node is what put
  it out of reach: on hardware, a transfer that failed around 15 KB was followed by a reset,
  and the node then appeared in no scan at all and had to be recovered over a cable. It is
  now left alone once its bank is gone — still advertising, still retryable from the phone —
  and reset only while it still has firmware to go back to.
- **Packet writes no longer wait for a confirmation that may never come.** Android reports a
  no-response write as complete when it frees the buffer, and that report is a courtesy, not
  a guarantee. Waiting on it deadlocked the transfer: on a live ProMicro every control-point
  write completed instantly while the very first packet write after them never did. Packets
  now pause briefly for the buffer and carry on — flow control for the image is the node's
  own receipt notification, which is a fact about what the node received rather than about a
  local buffer — and the number of unconfirmed writes is logged. This is what got the first
  bytes of an over-the-air image onto a node.
- **A transfer can no longer hang inside a single packet.** Every step had a time limit
  except the one that actually sends data: each write waits for the Bluetooth stack to
  confirm it, and nothing bounded that wait. A confirmation that never arrived left the
  transfer suspended behind a progress bar frozen at its last byte count — no error, no
  disconnect, nothing to do but kill the app, which is precisely what a live ProMicro looked
  like at 14,800 of 372,044 bytes. Writes are now bounded too, and a lost one is reported as
  what it is: a link that is up but not moving data.
- **The short connection interval is asked for again as the transfer runs.** It is an
  advisory request that stacks are known to let lapse, there is no way to read the interval
  back, and a 400 KB image takes minutes — so asking once at the start was a bet on the part
  of the transfer that was never in doubt.
- **A node that announced its update address is now found by it.** `start ota` answers with
  the node's own address, and the search applied the bootloader's +1 to it — an address
  nothing was using yet. The node was still found, but by its `_OTA` NAME, which every other
  node in update mode also wears: with two of them nearby neither could be told from the
  other and the one whose address we had been given was reported as not advertising. An
  announced address now means that node in either state, its own or its bootloader's.
- **The radio in your pocket is let go of before another node is flashed.** It was kept
  connected throughout, so a sustained 400 KB transfer ran alongside a second live Bluetooth
  link carrying mesh traffic, sharing one controller — while the transfer was asking for the
  fastest connection interval it could get. It is not part of the transfer and is now
  released before it starts, and reconnected afterwards as before.
- **A node's board, firmware version and update address survive reconnecting to the radio.**
  The radio owns the contact list and the app re-reads it on every connection, keeping only
  the unread count and the last message time — so everything the app had learned about a node
  and the radio had not was wiped each time. That is the opposite of what those fields are
  for: they exist because the moment they are needed is the moment the node can no longer be
  asked. The failure it produced was as bad as it sounds — a repeater announced its update
  address, the app stored it and showed it, the transfer failed, the radio reconnected, and
  the recovery dialog for a node now sitting in its bootloader said no address had ever been
  recorded.
- **The update log no longer erases itself.** Progress was written down on every
  acknowledgement — about 1,860 lines for one image, against a 500-line buffer — so by the
  time a flash failed, its log held nothing but the progress bar. Everything a failure has
  to be read against, including the failure's own context, had been pushed out by it.
  Progress is now sampled and carries the transfer rate, which is the number that separates
  a link that slowed down from a node that stopped dead.
- Failures say what state the node is left in. A node that loses the transfer part-way is
  waiting in update mode, not bricked — which is the difference between a retry and a trip
  up a mast. The OTAFIX bootloader is recommended where it matters.

## 0.7.16

An audit release. One feature landed (channel codes now carry their region), and the rest
is nineteen defects found by reading the code against the MeshCore firmware rather than
against our own assumptions. Several were silent in the direction that matters — they made
something look like it had worked.

- **A shared channel code carries its flood scope, end to end.** `region_scope` is
  documented and the mainstream app has emitted it since v1.47.0; this app parsed the key
  and the name and dropped the scope, so joining flooded every message across the whole
  mesh while its owner believed it was contained. The scope is now shown **before** you
  join, applied to the slot afterwards, and included on the codes you share.
- **A code whose region this app can't use says so.** Previously identical to a code with
  no region at all: same dialog, same "Channel added". You now get told, in the join
  dialog, that the channel will flood the whole mesh.
- **Re-sharing a scoped code to someone who already has the channel now applies the
  scope.** This is how a community rolls a region out, and every existing member used to
  get "Already in this channel" and carry on flooding globally.
- **Deleting a channel forgets its region.** The radio hands the freed slot straight to
  the next join, which inherited the scope of a channel you had deleted.
- **Favouriting a contact, or pinning its route, no longer stops its adverts.** The
  firmware treats that field as the contact's last advert timestamp and drops anything
  older as a replay, so writing the phone's clock into it silently froze the contact's
  name, location and route until the node's own clock caught up. Radios without GPS lose
  their clock, so this was not a rare case.
- **A contact scanned from a QR starts with no advert timestamp**, so the node's very
  first advert is accepted rather than discarded.
- **A channel can no longer be overwritten by a failed channel read.** One unanswered slot
  read used to erase the rest of the list, after which the next join was handed a slot
  that was actually in use — and a channel's key cannot be recovered from the radio.
- **Direct contacts stop reporting a pinned route they never had.** On a mesh with 2-byte
  hop hashes — the common case — a zero-hop path read as "Manual" routing.
- **Inbound messages are no longer dropped in the first moment after connecting.**
- **A `meshcore://` link pasted with a sentence after it works.** Previously "Malformed
  contact code", which blamed whoever sent it.
- **A link with anything after a `#` works** — every other client's parser cuts there too.
- **On a device whose keystore refuses to store secrets, the app says so** instead of
  silently not saving a password, and a channel whose key can't be cached stays in your
  chat list rather than disappearing from it.
- **Node names ending in an emoji are no longer cut in half** when written to the radio.
- Region names are capped at the firmware's 29 bytes rather than 30 — one over was a scope
  that looked set on the phone and routed nothing on the air.

## 0.7.15

- **The QR scanner can read inverted codes — for the first time.** MeshCore apps in dark
  mode render white codes on near-black, and this app could not see them at all: it would
  sit on a live viewfinder indefinitely while every other scanner on the phone read the
  same code instantly. 0.7.11 claimed to have fixed this and did not. The hints were being
  set on a decoder the scanning library replaced a moment later, during the same startup,
  so they never reached a single frame. The scan type is now passed the way the library
  reads it.
- **Channel QR codes from other MeshCore apps work.** The channel key is called `secret`;
  this app asked for `channel_secret`, a name it had invented and also emitted — so its own
  codes were readable only by itself, and every channel share from anywhere else came back
  "Malformed contact code". Codes this app produced earlier still scan.
- **Spaces in a scanned name stay spaces.** A channel shared as `West+Michigan+GMRS`
  arrived under that name, plus signs and all.
- **Paste a code** — Nodes → ⋮. Other clients share contacts by copying a `meshcore://`
  link rather than showing a QR, and until now there was no way to give one to this app.
  It finds the link inside a pasted message, and everything after that is the ordinary
  scan flow, confirmations included.
- **Joining a channel you are already in no longer adds a second copy of it.** It matches
  on the key rather than the name, because the key is what a channel *is*. Duplicates cost
  one of the radio's eight slots and put inbound messages in an ambiguous thread.

## 0.7.14

- **Contact QR codes from other MeshCore clients import.** The `meshcore://<hex>` form —
  a shared raw advert, as Liam Cottle's client exports — was rejected every time with
  "Import failed (bad signature?)". The blob a radio exports is a whole packet, and this
  app was checking the signature as though it were the advert alone, so it read the
  packet's header byte as the first byte of the public key. It could never have matched.
  Reported as a regression; it was not one. That code has never imported, because nothing
  this app *emits* takes that form, so the only codes reaching the path came from
  elsewhere.
- **Spaces in a scanned name are no longer turned into `+`.** A contact shared as
  `name=Example+Contact` — the encoding in MeshCore's own QR documentation — arrived
  called "Example+Contact". A literal plus still survives if the sharing app escapes it,
  which conforming ones do.

## 0.7.13

- **Blocking a repeater no longer breaks its console.** It never could block a repeater —
  traffic through one carries the *original* sender's key, so there is nothing on the
  repeater's key to block, and that is a property of the mesh rather than something an app
  can change. What it did do was swallow the node's CLI replies before they were written
  down, silently, so the Console went quiet while the Settings form carried on working.
  Same node, same command, two screens, two answers. A block now never applies to a reply
  you asked for by name.
- **The Block action is gone from repeaters and sensors**, which send no messages of their
  own and so had nothing to block. Rooms keep it: a room's chat really does arrive as
  direct messages from the server, so hiding one has a real effect. It also stays visible
  on anything currently blocked, so a repeater blocked by an older build can still be
  unblocked.

## 0.7.12

- **A repeater that goes quiet now repairs itself.** When a node you are signed into stops
  answering, the app clears the route and re-establishes it, then retries — the thing you
  were doing by hand with Sign out / Sign in. It tells you while it works, and it does the
  free repair first: the probe is a login carrying **no password**, which is enough for a
  node that already knows you, so your credential does not go back on the air unless that
  fails. There is no session to expire on a repeater — a login is permanent and survives a
  reboot — so what re-signing-in ever fixed was the *route*, and only when it happened to
  go out as a flood. This does it deliberately.
- **Except when you pinned the route yourself**, where it says so instead. A pinned route
  cannot be repaired this way — the node only forgets a dead return path for a login that
  floods, and a pinned contact never floods — so it now fails in a third of the time and
  names the pin rather than spending ninety seconds and your password proving it.
- **The settings-QR generator estimates sensitivity and path loss.** A new tab gives
  receiver sensitivity and the loss a link can absorb across every spreading factor and
  bandwidth, with airtime beside it, and the summary line on the code page now says what a
  receiver on those settings can hear. There is deliberately no distance: turning path loss
  into range needs terrain, and a confident wrong number is worse than none.
- Internal: the access-list and neighbour replies are now matched to the request that asked
  for them, as the region lookup always was. Three of the four agreed and one did not.

## 0.7.11

- **"Scan settings QR…" is on both radio screens**, beside "Use a regional preset…" —
  Settings → Radio for the radio in your hand, and a repeater's own Radio panel for a node
  across the mesh. It existed before but only behind the contact-import buttons on other
  tabs, which is not where anyone goes looking to apply a settings code.
- **Scanning from the Chats button now does something.** The confirmation dialogs were
  drawn only by the Nodes screen, so a code scanned anywhere else set everything up
  correctly and then showed you nothing at all. That affected contact cards and channel
  shares as much as settings codes.
- **The scanner opens the right way up**, and — less visibly but worse — can now read
  dark-mode QR codes from every button. One launcher had been built by hand and missed the
  configuration that carries both, so it silently could not decode the roughly half of
  codes in circulation that are rendered light-on-dark. A scanner that reads nothing looks
  exactly like a code that is bad.
- Internal: the scanner options, and the hex helpers behind them, are each defined once
  now. Four copies of "is this hex" had already drifted apart without anyone noticing,
  which is how the scanner bug happened in the first place.

## 0.7.10

- **The access list works.** It never had. "Fetch access list" asked over the air for
  something only the node's own serial console can answer, so the node replied `??: acl` —
  which reads like firmware too old for the feature rather than a question it could never
  have answered. It now uses the request the firmware provides for this, and shows who has
  Admin, Read-write, Read-only or Guest on a repeater.
- **…and it no longer invents an entry.** The first working version showed a fourth row,
  `000000000000  Guest` — an account with access to your repeater that does not exist. The
  reply is encrypted and therefore padded, and the padding happened to be exactly the size
  of one more entry. Inventing a row in an access list is worse than dropping one.
- **Unused channel slots are out of the Chats list again.** Empty slots were reappearing as
  "Channel 2", "Channel 3", "Channel 4" — conversations that do not exist. Two parts of the
  app disagreed about what counts as a channel, and opening Settings → Channels (which
  reads every slot the radio has) put the blank ones back. There is now one rule, which
  also handles a channel you have just cleared: it leaves the list instead of lingering as
  a nameless row.
- Settings → Channels lists your real channels and an **Add channel** button, rather than
  every empty slot the radio happens to have.

## 0.7.9

- **Join a mesh by scanning a QR.** A code can now carry an area's radio settings —
  frequency, bandwidth, spreading factor, coding rate, path-hash width and an optional
  flood region. Scanning shows every value and asks before applying: nothing in a QR is
  signed, and these are the settings that decide whether your radio is on the mesh at all.
  Generate codes at
  <https://thatsfguy.github.io/meshcore-mobile-app/settings-qr/> — it runs entirely in your
  browser and the image carries the settings as readable text, so a printed code still says
  what it does.
- The code deliberately carries **no transmit power and no channel keys**. Power is the
  legal limit where *you* are standing, not a property of the mesh; a channel key would make
  the code a secret rather than something safe to pin to a noticeboard.
- **Applying radio settings to a repeater now offers to reboot it.** The node saves them and
  keeps running on the old ones until it restarts — the firmware says so, answering
  "OK - reboot to apply" — so without the prompt a preset looked like it had done nothing.
  This affects remote nodes only; your own radio applies immediately.
- The dropped **USA Rural / USA Suburban** presets are gone from the app and live on the
  generator page instead, alongside MeshCore's USA/Canada default. Hardcoded frequencies go
  stale silently and need a release to fix; a page you can edit does not.

## 0.7.8

- **New preset: USA Rural** — 906.375 MHz, 250 kHz, SF9, CR4/5, 22 dBm. Wide bandwidth at
  a higher spreading factor, for sparse coverage where hops are long and few. It is a
  local addition, not from the reference table the other 47 came from, and the file says
  which entries are which.
- **Presets can now be applied to a repeater or room, not just the radio in your hand.**
  "Use a regional preset…" is in the remote Radio panel, and the confirmation names the
  node it is about to retune. It also warns what that means: the node changes the instant
  the command lands, and this radio can no longer reach it to undo the mistake — you would
  have to match the settings here, or go to the node.
- Applying a preset remotely sends TX power **before** the retune, because everything
  after the retune goes out on parameters the node has already left.

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
