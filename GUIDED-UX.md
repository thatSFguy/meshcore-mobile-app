# Guided UX — answering what power users ask

Status (2026-11-15): **question 1 built** and driven on the phone; 2–4 are sketches.

## Why

The full app has the tools — ping, trace, path history, heard-via, neighbours, regions, Who
repeats me — but leaves the user to know which tool answers which question, run it, and add the
results up in their head. The owner, who uses all of it daily, put it this way: the app is "not
optimized based on user actions".

The example that started this: Chicago nodes are heard all day around Grand Rapids, and almost
never answer. Every one of them shows up as a node to look at and maybe add. A node you can hear
but cannot reach is not a contact; it is noise. The app never says which kind a node is.

So full mode should start from the question and come back with **an answer, the evidence for
it, and what to do next.**

## The questions

Confirmed by the owner as the ones power users actually ask:

1. **Can I talk to X?** ← build first
2. **Why didn't my message get through?**
3. **Which repeater is carrying me?**
4. **Is my node set up well for this area?**

## Rules for every answer

- **Never claim more than the evidence.** Silence is not "unreachable"; it is "no answer". The
  same discipline as heard-via, which names a hop only when exactly one node matches.
- **Say what the answer rests on and how old it is.** Routes change; a delivery last month is
  "worked before", not "works".
- **End with the next step**, or say there isn't one.
- **Cost airtime only when asked.** Answers come from evidence already collected. Anything that
  transmits (a check) runs on a tap, never in the background.
- **The answer lives on the thing**: on the node's row, in its sheet, on the failed bubble. It
  is not a new screen.

---

## 1. Can I talk to X?

### What "talk" needs — from the firmware

Two things, and the app can only see one of them:

- **Radio paths both ways.** Hearing them proves nothing about their hearing you.
- **They must have you as a contact.** A radio decrypts a direct message only from senders in
  its own contact list and drops anything else without a word (`BaseChatMesh.cpp:203`,
  `searchPeersByHash`). So a Chicago node in perfect radio range still never gets your DM
  unless its owner has added you. From your side that looks exactly like a radio failure.

That second point is half of the Chicago experience, and the app should say it out loud.

### Evidence, strongest first

| | Evidence | Proves | Already collected? |
|---|---|---|---|
| A | A DM to them was **confirmed delivered** (end-to-end ACK) | Both directions, and they have you as a contact | Yes — `path_history.successes`, `lastWorkedAt` |
| B | They **answered a request**: login, status, telemetry, ping, path discovery | Both directions | Partly — the answer is shown, not kept |
| C | The radio **holds a route to them that they sent** | They heard a flooded packet from you at least once | Yes, as the contact's `out_path`. The firmware stores a route only from a path return the other node sends after receiving our flood (`BaseChatMesh.cpp:250-254`, `:353-357`). **Not evidence if the user set it by hand** in Routing; the app must remember which routes it set. |
| D | You **hear them** (adverts, messages, heard-via) | Their direction only | Yes |
| E | Nothing | — | — |

### What the user sees

One line on the node's row and at the top of its sheet:

- **Two-way · confirmed 2 d ago** (A or B, recent)
- **Two-way before · last confirmed 5 weeks ago** (A or B, old)
- **They've heard you** (C only)
- **Heard only · nothing you've sent has reached them** (D, and A–C never happened)
- **Not tried** (D, but nothing was ever sent to them)

### Where the answer changes the app

- **Nodes list:** two-way nodes first. Far-away heard-only nodes (Chicago) fold into a collapsed
  **Heard only** group. They stay one tap away without crowding the list or needing a block.
- **Before adding someone:** "You hear them through 6 hops. Nothing you've sent has reached
  them. Add anyway?"
- **On a failed DM:** see question 2.
- **Check reachability** in the sheet (one tap, transmits once):
  - **Repeaters and rooms:** path discovery or ping. An answer returns both routes, theirs to
    you and yours to them, with nothing shown on their side (`companion_radio/MyMesh.cpp:1651-1680`,
    `:781-805`).
  - **People:** the same path discovery, but a companion answers it only if its owner allows
    telemetry, and the firmware default is to deny (`NodePrefs.h:26`, 0 = deny; no reply when
    denied, `BaseChatMesh.cpp:314-316`). So silence from a person reads "No answer — they may
    not allow it", never "unreachable", and the next steps are: send a DM (an ACK is proof), or
    ask on a shared channel with Message privately (they must add you anyway).

### To build

- `Reachability` in `shared/presentation`: a pure function from the evidence to the state and
  wording, so it is tested without a radio and iOS inherits it.
- Keep request answers (B) per node with a timestamp, and record when a route was set by hand.
- Path discovery: `CMD_SEND_PATH_DISCOVERY_REQ` (52) / `PUSH_CODE_PATH_DISCOVERY_RESPONSE`
  (0x8D) are not implemented yet. Builder tested against the firmware's reader, per CLAUDE.md.
- Tests: every evidence combination, ages around the cut-off, a hand-set route never counting as
  proof, and positive controls that a delivered DM must yield two-way.

---

## 2. Why didn't my message get through? (sketch)

Turn the ✗ into a diagnosis from evidence the app already has:

- **No repeater repeated it** — the engine already logs our own packet being repeated ("OURS,
  repeat sighting"). None heard means it never left your area: antenna, placement, or your
  radio's settings.
- **Repeated, but no delivery** — it got out. Either it died further along, or they don't have
  you as a contact. If they are heard-only (question 1), say so.
- **The direct route failed and the flood worked** — the stored route went stale; the app
  already falls back. Say so, and that it was repaired.
- **Flood scoped to a region** — if your messages carry a region the far side's repeaters don't
  forward, they stop at the border. The app knows its own scope; it can at least ask the
  question.

## 3. Which repeater is carrying me? (sketch)

Rank repeaters by how often they repeat *your* packets (repeat sightings) and carry your
delivered DMs (path history), with signal. Who repeats me already does part of this, from
signed adverts. The answer: "Hill carries 80% of what you send; 13 Mile the rest."

## 4. Is my node set up well for this area? (sketch)

A checklist against what the radio hears around it:
- radio preset matches the neighbours;
- path hash width matches the mesh;
- flood scope makes sense;
- advert interval;
- clock set;
- name doesn't collide with a heard node.

Each item says good / worth a look, with a one-tap fix where the app can apply it.

---

## Decisions (owner: "go with your proposals", 2026-11-15)

1. Proof counts as **two-way for 7 days**, then "two-way before".
2. Far heard-only nodes are **folded by default**. "Far" is **4+ hops, or 100 km+ away** when the
   position is known. Hops alone was the proposal, and the phone showed it was wrong: Chicago
   nodes 134–151 mi away arrive in 3 hops on this mesh. With distance added, 29 of 38 heard
   contacts fold away.
3. **Check is offered for people and sensors**, with the "no answer" wording. Repeaters and
   rooms keep Ping, which now also records proof.
4. Order after question 1: **2, 3, 4**.

## What question 1 shipped as

- `presentation/Reachability` — the rules, tested (including a delivered DM must give two-way,
  a hand-pinned route never counts, unknown hops or position is never "far").
- `protocol/PathDiscovery` + `Frames.sendPathDiscovery` + `MeshCoreEngine.discoverPath` —
  tested against the firmware's reader and writer.
- Evidence: `path_history` deliveries (grouped query, no schema change), plus answers to ping,
  sign-in (a refused password counts — it answered), telemetry and Check, kept per own radio in
  preferences. "Heard them" is the advert timestamp, NOT `lastmod`: the firmware stamps `lastmod`
  with "now" on every add, rename or favourite (`companion_radio/MyMesh.cpp:1331-1340`).
- Nodes list: a one-line verdict on each contact, the "Far away, heard only" fold, and "Add
  anyway?" for far nodes. Sheets: the verdict first, and Check for people and sensors.

Side finding, not fixed: the "Last heard" age on contact rows comes from `lastmod`, so renaming
or favouriting a contact makes it look freshly heard.
