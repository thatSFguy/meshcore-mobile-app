# Simple mode

Simple mode is MeshCore Hardened with everything except talking hidden. It exists so the app can
be handed to people who are not radio enthusiasts — including a **first-responder team working in
a closed group** — who need to install it, connect their radio, and talk on the group's channel.
Repeaters, routes, radio parameters and firmware are somebody else's job.

This document is the reference for what Simple mode does, how two people in it start a direct
conversation, and what that does and does not promise. The rules themselves live in code, where
they are tested: `shared/.../presentation/AppMode.kt` (what is shown), `presentation/DmRequest.kt`
and `messaging/DmRequests.kt` (direct-message requests).

## Turning it on and off

Choose it at first run, or from the top tile of **Settings** ("Simple mode"). The same tile, which
then reads "Simple mode is on", switches back.

**Simple mode hides; it never changes anything.** Contacts, channels, messages and every radio
setting stay exactly as they are, and switching back shows all of it again. That is why switching
back needs no PIN: there is nothing behind the switch that Simple mode protects, and someone stuck
in a mode they cannot leave is worse off than someone who wandered into the full app.

## What is shown

| | Full | Simple |
|---|---|---|
| Tabs | Chats · Nodes · Map · Settings | Chats · Settings |
| Public channel | shown | **hidden** — every radio has it, and one wrong tap would post a responder's message to the whole mesh |
| Private channels | shown | shown |
| Direct chats with people | shown | shown |
| Repeater consoles, room servers | shown | hidden |
| Hop count, SNR, repeat badges | shown | hidden |
| Routing, path and node-detail actions | shown | hidden |
| Mute, unread, clear, reply, react, copy, delete | shown | shown |
| Chats ⋮ menu | adverts, add channel, community QR | **My code…**, **Scan a code…**, **Paste a code** |
| Settings tiles | all | Connection, Identity, Channels, Blocking, App, Backup, About |

A direct thread whose contact the app cannot classify yet is **shown**, not hidden: hiding a message
from someone because we do not know what they are is the wrong way round.

## How a group is set up

The group talks on **one shared private channel**. Everyone joins it by QR code or `meshcore://`
link (Chats ⋮ → *Scan a code* / *Paste a code*). Nothing is added without a confirmation tap, and a
link opened before the radio is connected waits until it is.

**Auto-add stays off.** Otherwise a radio's contact list fills with every node it hears. That was
decided on purpose: in a closed group, strangers must not become contacts, and no flooding beats
being reachable by anyone.

Direct messages are optional and rare — a couple of people in a group of fifty, set up pair by pair.
There is no coordinator "group setup code"; that was dropped.

## Starting a direct conversation

### Why it is a handshake

A MeshCore radio decrypts a direct message only when the sender is **already in its contacts**
(`BaseChatMesh.cpp:203`, `searchPeersByHash`). With auto-add off, a first message from someone new
is dropped by the radio before any app sees it. So a Signal-style "message request" — send it, the
other side accepts — cannot exist as a direct message: there is nothing to show. **Both** radios have
to add each other first, and the request travels over something they already share.

There are two ways to do that, and both end the same way.

### Over the group channel

Say Alice wants to message Bob.

1. **Alice asks.** She taps **Message privately** under one of Bob's channel posts. If exactly one
   person in her contacts already has that name, the app just opens that chat. Otherwise it
   confirms — *"This posts a short request on this channel. Everyone here will see that you
   asked."* — and posts:

   ```
   Alice: DM request for @[Bob] mch:r:<Alice's 64-hex public key>
   ```

   The app remembers that it asked Bob, for **7 days**.

2. **Bob sees it.** In his thread it reads *"Alice wants to message you privately"*, with
   **Accept** / **Decline** and the first 8 hex digits of Alice's key, for two people who care to
   compare. Everyone else sees *"Alice asked Bob to message privately"*, with no buttons. Anyone
   running another MeshCore app sees an ordinary @-mention.

3. **Bob accepts.** His app adds Alice to his radio first, and **only if that worked** posts:

   ```
   Bob: DM accepted @[Alice] mch:a:<first 8 hex of Alice's key>:<Bob's 64-hex public key>
   ```

   (An accept posted without the contact would tell Alice she can write to someone whose radio
   cannot read her.) **Decline** posts nothing; Alice's request simply runs out after 7 days.

4. **Alice's app adds Bob** — but only if *all* of these hold:
   - the accept carries the start of **her** key;
   - she has an **outstanding request** to the name it came from;
   - it is not her own post echoed back.

   An "accepted" that nobody asked for adds no one. Otherwise anyone on the channel could add
   themselves to anyone's radio.

Now both radios hold each other, and direct messages work both ways.

### By code or link

Alice shows **My code…** (a QR, or *Send link* to text it). Bob scans or opens it and confirms, and
Alice is added to his radio. Bob's app then offers to tell Alice on a shared channel. That posts
the same `DM accepted` line, and Alice's app adds Bob.

Showing your code is an invitation to whoever scans it, so for **7 days** afterwards an accept
carrying your key's prefix adds its sender, **whatever their name**. See *Limits* below.

### If the acceptance is lost

Channel posts are flooded, never acknowledged, and can be lost. If Bob's acceptance never reaches
Alice, Bob has Alice but Alice does not have Bob: Alice → Bob works, but Bob → Alice is dropped by
Alice's radio, and Alice's app is still waiting.

**Alice asks again.** A request that arrives *after* Bob last answered her is a new question, so
Bob's Accept / Decline come back for it (and only for it — the post he already answered stays
answered). Accepting again re-posts the acceptance and does **not** re-add Alice: re-adding would
reset the route and advertised name Bob's radio has learned for her since. Bob sees *"Answered Alice
again."* The same applies after a Decline: asking again asks again. (Fixed 2026-11-26. Before that,
"answered" was remembered per person, and a second request read as already answered.)

## Adverts

**Nothing in this flow sends an advert, and nothing in it needs one.** A direct message's encryption
comes from the two public keys alone, and the channel already carried them. A contact added by
request or by code starts out as:

- **named** with what the person showed on the channel or in the code — display text, not a signed
  name;
- **flood-routed** — the first message floods, and the reply teaches the route;
- **timestamp 0**, on purpose: stamping it with the phone's clock would make the radio's replay
  guard reject that node's real adverts until its own clock caught up (`MeshCoreEngine.addContactFromCard`);
- **without a position**.

When that node later advertises and the advert is heard, the radio should update the existing
contact with its signed name and position, since auto-add only governs *new* contacts. (Check the
firmware's `onAdvertRecv` before relying on that.)

Simple mode has no **Send advert** menu entry; the full app keeps it on the Chats ⋮ menu. So
someone in Simple mode is not announced to the mesh by the app. That does not matter for the
group's channel or for direct messages, but they will not appear on other people's maps or node lists
unless the radio advertises by itself.

## What it promises, and what it does not

The goal is **keeping noise out, not secrecy.**

- **Channels are obfuscated, not secure.** AES-ECB with a 2-byte MAC (MESHCORE_PROTOCOL §9). Anyone
  holding the channel key reads everything on it. Treat the group channel accordingly, especially
  for first-responder traffic.
- **A request is public on the channel.** Everyone there sees who asked whom, and both public keys.
- **Names are not identities.** Channel sender names are text anyone can type. A request goes to a
  *name* (so two people called Bob both get the buttons), and the reply goes to the requester's
  *key*. Within the 7-day window, someone posting as "Bob" with Alice's key prefix would be added
  under that name. The 8-hex key prefix on the Accept card is how two careful people check.
- **"My code" opens a wider window.** For 7 days after you show it, *any* accept naming your key's
  prefix adds its sender. Once you have posted a request, your full key is public on that channel.
- **Size.** A channel post is capped at 160 bytes *including* `"<sender>: "`, and the firmware
  truncates anything longer silently. Both forms fit with 31-byte names on both ends, and the app
  checks the fit before sending rather than risk posting a truncated key.

## Not yet seen on hardware

As of 2026-11-26, driven on one phone but not with a second person: receiving a request and pressing
Accept / Decline, the automatic add when an acceptance arrives, and the re-ask recovery above. Do
these with two phones on the group channel before relying on them.
