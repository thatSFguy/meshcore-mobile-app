# Rebuild Playbook

**For:** replacing an app people already like, because something under the hood is wrong —
too many dependencies, a bad security posture, telemetry, a dead maintainer, a licence.

**Not for:** building something new, or something you think should work differently. If
you have a better *product* idea, this playbook is the wrong tool and will hold you back.

Written after [`LESSONS.md`](LESSONS.md) — a rebuild that shipped five releases, passed 509
tests, and ended up too complex for the person who commissioned it. Everything here exists
to stop that specific outcome. The rules are deliberately blunt: the failure mode is
drift, and drift feeds on ambiguity.

---

## 0. The premise, stated plainly

The incumbent has **one asset you cannot rebuild and must not damage**: the reason people
use it. Almost always that's ease of use — an interface that has already been through
contact with real users and survived.

Your objection is to the **substrate**: dependencies, crypto, data handling, licensing.

**These are orthogonal.** Nothing about fixing the substrate requires touching the
interface. The entire failure mode of this kind of project is treating them as one job and
redesigning the interface "while we're in there."

> **Prime directive:** the incumbent's UX is a **specification to be matched**, not a design
> problem to be re-solved. You are not designing. Any deviation requires a written reason.

This reverses the usual default. In the project that produced this playbook, matching was
the exception and deviating was free — which is exactly how you end up with a repeater
admin screen of six tabs and a settings page of ten accordions, neither of which anyone
designed.

### "Better" must be better *for the user*

Your improvements are **invisible**. Nobody can see that you removed 40 dependencies or
that the database is encrypted. The user experiences only the interface.

So every unit of UX you spend buys the user *nothing they can perceive*. The exchange rate
is zero. Which means:

**The UX cost of your rebuild must be zero.** Not "small." Zero. If the app is harder to
use, you have made it worse, and no amount of substrate quality compensates — you will
lose the argument with users, and eventually with yourself.

---

## 0.5 When trust *is* the product

The usual reason to do this at all: the incumbent is pleasant but you don't trust it —
bloated dependency tree, sloppy data handling, telemetry, an attack surface nobody has
looked at. The bet is that people who care about privacy will prefer yours.

It's a good bet. It is also the one most often lost, and always the same way.

### You are not selling the properties. You are selling a believable promise.

No user audits your crypto. No user diffs your dependency tree. They decide whether to
**believe** you, on evidence they can actually check. That makes trust three jobs, and
engineering is only the first:

1. **Be trustworthy** — the actual work. Necessary, and by itself worth nothing to a user.
2. **Be verifiable** — artefacts a sceptic can check without reading your code.
3. **Be believed** — claims that are legible, specific, and never larger than the truth.

Most rebuilds do (1) exhaustively, skip (2) and (3), and then find that nobody switches.
Doing (1) well is what makes you *deserve* the users. It is not what gets them.

### Usability is the entry ticket, not the competition

Your differentiator is trust. Your **floor** is still ease of use, because the market you
actually want is not "people who will suffer for privacy" — that market is tiny and
already served. It's *"people who will take privacy if it costs them nothing,"* which is
orders of magnitude larger and is won or lost entirely on the interface.

Every privacy tool that lost, lost here. PGP was more trustworthy than every messenger of
its era and reached almost no one. Signal won by being as easy as the thing it replaced,
and privacy came along for free.

**A tool nobody uses protects nobody.** The security work only converts into real-world
protection through adoption — which means UX is not in tension with your mission, it is
the delivery mechanism for it.

The project behind this playbook is the proof, in miniature. Verified advert signatures,
secrets in the keystore, an encrypted database, channels honestly labelled as obfuscated
rather than secure, no analytics, no servers. Genuinely better on every axis its author
cared about. Its author stopped using it. **Users protected: zero.**

### Overclaiming is a security bug

Trust is earned slowly and destroyed instantly, so for a trust-differentiated product the
accuracy of your claims is a **security property**, not marketing hygiene.

This repo got the hard half right: the name note recording that *"Hardened describes this
build's posture, NOT a protocol guarantee,"* and channels labelled obfuscated-not-secure
in the UI. It then got the easy half wrong — a README saying "v1 shipped" over an app with
a dozen visible defects, and a changelog crediting a release with four features that
landed after its tag.

Same failure, opposite direction. Someone who catches you overstating a shipping date has
no reason to believe your threat model.

> **Rule:** publish what you do **not** protect against, prominently, before anyone asks.
> A threat model with honest exclusions is more persuasive than any list of features.

### Fewer dependencies is not automatically safer

The property you want is **small, auditable, maintained** attack surface. Dependency
*count* is a proxy for it, and like every proxy it breaks when you optimise it directly
(see §8.1 — whatever you count, you maximise).

Replacing a mature library with your own code trades a reviewed, widely-used
implementation for an unreviewed one that has fixed none of the bugs the original already
fixed. That is frequently a net loss in both safety *and* polish, and hand-rolled UI
components are where the "feels amateur" comes from.

> **Rule:** budget per dependency, not in aggregate. For each one: what does it do, is it
> maintained, what does it reach (network, disk, reflection), and what would writing it
> myself actually cost in defects? Remove the ones that fail that test. Keep the rest and
> say why in the SBOM.

### Trust artefacts — build these, they are product features

Ship these alongside the app; they are how (2) and (3) get done:

```
[ ] SBOM published, with a one-line justification per dependency
[ ] Threat model doc — including, explicitly, what you do NOT defend against
[ ] Reproducible builds, or documented why not
[ ] Permission list, minimal, each one explained in-app at the point it's requested
[ ] Network behaviour stated exactly ("no outbound connections except map tiles,
    which can be disabled") — and demonstrable: the app works in airplane mode
[ ] Every security claim uses the weakest accurate word (obfuscated, not encrypted)
[ ] An audit — third-party if affordable, self-conducted and published if not
[ ] Release notes that are true about the build they are attached to
```

That checklist is roughly a week of work and does more for adoption than a quarter of
features.

---

## 1. Before any code: the charter

Write these five things down. If you cannot, you do not have a project yet.

### 1.1 Name the asset

Install the incumbent. **Use it as a real user for a week.** Not a survey — actual daily
use. Then write the five things that make it pleasant.

If you cannot name what people like, you cannot preserve it, and you will destroy it by
accident and never know which step did it.

### 1.2 Name the defect — falsifiably

Not "too many dependencies." Instead:

> 312 transitive deps; 14 pull native code; 3 are unmaintained >2yr; the analytics SDK
> phones home on launch.

Not "security flaws." Instead: a written audit, finding by finding, each one reproducible.

**Test:** could someone else verify every claim on your list without asking you? If not,
you have an aesthetic preference, not a defect list — and aesthetic preferences expand
without limit once you start work.

### 1.3 Write the charter sentence

One sentence, this shape:

> **"[Incumbent], but [specific defect fixed], and otherwise indistinguishable."**

The **"otherwise indistinguishable"** clause is load-bearing. It is the thing you point at
in month three when you're about to add a feature the incumbent doesn't have.

### 1.4 Pick your reference — source beats screenshots by an order of magnitude

Before reverse-engineering anything, **inventory what you already have source for.** Write
it down. Re-ask whenever scope changes.

| Reference quality | What you can do |
|---|---|
| Full source | Read the widget tree. Exact paddings, flows, edge cases. Port it. |
| Open protocol/API only | Match behaviour; reverse-engineer the UI from observation. |
| Closed binary | Screenshots and driving. Every measurement is a guess. |

**If a source-available equivalent exists, clone that one's UX, even if it's not the most
popular one.** The fidelity you gain is worth more than the polish you lose.

### 1.4a Take the navigation graph in the same sitting as the feature list

Having a good reference is not enough — you have to copy the right layer from it.

The project behind this playbook had a fully open-source client of the same protocol in a
sibling directory, and *did* use it: v1 scope was explicitly a pruning of that client's
feature inventory (~26 screens). What it took was the **list of screens**. What it never
took was **how those screens connect** — the reference fronts its device admin with a hub
and five focused screens; the rebuild shipped one screen with six tabs.

Worse, the features were then implemented onto a UI foundation borrowed from a **third**
app, for a different protocol. So each feature had somewhere to land without anyone ever
choosing where it should live.

**Rule:** a feature list and a navigation graph are **one artefact**. Extract them
together or you will faithfully reproduce *what* the reference does while inventing your
own — worse — answer to *where it lives*.

### 1.4b Never change your reference standard mid-project

In the same project, the standard moved mid-build: a curated ~26-screen scope was
superseded by a 64-row matrix scraped from a different, closed app's class names, with the
handover written into the scope doc — *"where this document and PARITY.md disagree, PARITY
wins."*

Scope tripled in one edit. It was documented and approved, which is exactly what made it
invisible: it didn't look like creep, it looked like planning.

**Rule:** the reference named in your charter is the reference. Changing it is a **new
project** and gets the same scrutiny as starting one — §1.1 through §1.5, again, including
whether you still want to.

### 1.5 Decide the audience

Write down one of:

- **"A tool for me."** Then stop comparing yourself to the incumbent's feature list. Build
  the ten things you use. Ship. Done. Note that this choice makes the trust artefacts of
  §0.5 pointless — nobody needs convincing but you.
- **"A replacement others should adopt."** Then UX parity is a hard requirement, not a
  phase, and you are signing up for design work you may not want.

They justify opposite investments. Drifting between them — which is the default, because
nobody ever decides — gets you the costs of both and the benefits of neither.

---

## 2. Before any code: the reference capture

Produce these artefacts **first**. They are the spec. Budget real time; this is not
overhead, it is the requirements document.

1. **Screen inventory** — every screen, sheet and dialog, with a screenshot. Light and
   dark, empty and populated. Number them.
2. **Navigation graph** — how you reach each screen from each other. This is the thing
   users actually feel, and the thing feature-list copying destroys.
3. **Task traces** — for the ten most common tasks, the exact tap sequence, **counted**.
   ("Send a message to a known contact: 3 taps.") These become acceptance criteria.
4. **Component inventory** — the widgets that recur. Build these once, properly, before
   any screen.
5. **The do-not-copy list** — read the incumbent's issue tracker. Their known-bad screens,
   their dark patterns, and the specific defects you are fixing. Without this you will
   faithfully reproduce bugs they already know about.

> If the reference is closed-source, drive it with `adb`/automation and script the capture.
> A day here saves a month of guessing.

---

## 3. Build order

### Phase A — Shell

Navigation, theme, the component inventory, and every screen present but empty.

**Gate:** you can reach every screen in the inventory, and the navigation graph matches.
No feature logic yet.

### Phase B — One task, end to end

Pick the single most common task. Make it work completely, on real hardware / real data,
at the reference's tap count.

**Gate:** you'd rather use your app than the incumbent *for that one task*.

If you can't reach that gate on task one, stop and fix the shell. It will not get easier
with thirty more features in the way.

### Phase C — Task by task

Repeat. **Tasks, not features.** A task is something a user wants to accomplish; a feature
is something you implemented.

### Phase D — Substrate

Your actual reason for existing. Do it continuously, not at the end — but never let it
justify a UX deviation.

---

## 4. The per-feature gate

A feature is **not done** until all six are true. No exceptions, no "I'll come back to it."

1. **Driven on real hardware / real data.** Not a test, not a mock, not a simulator.
2. **Tap count within ±1 of the reference** for the task it serves.
3. **At least one test pinned to a value from outside your own codebase.**
4. **It adds no control the system could determine itself** (see §6.1).
5. **It did not add a tab, accordion section, or mode to an existing screen** (see §6.2).
6. **Its copy fits the label budget** (see §6.3).

Print this. It is the whole playbook compressed.

---

## 5. Reimplementation correctness

Applies whenever you're reproducing a wire format, file format, or API.

### 5.1 Read the *sender*, not the type name

Names in reverse-engineered protocols are frequently wrong, because whoever wrote them was
also guessing. The authoritative answer is the line that **produces** the value.

Real example: a field called `freqHz` everywhere in the ecosystem is computed
`(freqMHz * 1000)` — it's **kHz**. Copying the name into the spec, the model, the frame
builder and the UI label meant sending values 1000× too large. Every regional preset was
rejected by the radio, and it took a user report to find.

**Rule:** for every field, paste the producing line into your spec as evidence.

### 5.2 When a documented range and a field name disagree, believe the range

The same spec recorded `freq_hz 300 000–2 500 000` — correct — and annotated it *"firmware
uses Hz here."* 300 000 Hz is not a radio band. The evidence was right there and the guess
beside it won for months.

**Rule:** a `?` or "probably" in a spec is a blocking TODO, never a footnote. Uncertainty
recorded next to correct data reads as resolved.

### 5.3 Never cache a derived value

Four separate defects in one project, all the same shape: a value derived from a *context
property* computed once at the wrong value and then carried around as truth.

**Rule:** if X is derived from context, store the *source* and derive at use. Caching is a
bet that the context is stable — make that bet explicitly or not at all.

### 5.4 Steal their test vectors

If the reference is open source, its test suite is **ground truth you cannot generate
yourself**. Port the vectors even if you port nothing else. This is often the single
highest-value hour in the project.

---

## 6. UI rules

### 6.1 Never ask the user for something the system already knows

The archetype: a *"Guest (read-only)"* checkbox beside a password field, in an app where
the server **already reports** which access level it granted. The reply byte was parsed and
discarded; the session's rights came from the checkbox. So it couldn't work — tick it with
an admin password and the UI locks controls the server would allow.

The incumbent had no checkbox. You type a password; the server decides; the UI shows what
you got.

**Rule:** for every input, ask *"could the system determine this itself?"* If yes, it is
not a setting — it is a bug with a label. This single question would have removed a
third of the complexity complaints.

### 6.2 Screens are cheap; tabs are not

Complexity accretes into whatever screen is nearest, because adding to an existing screen
feels free and creating one feels like work. It is the opposite.

**Rule:** "which screen does this belong on?" is a required question per feature, with *"a
new one"* as a normal answer. A screen growing its **third** tab is the signal to split it
into hub-and-spoke, not to add a fourth.

### 6.3 Say the hard thing once, loudly — not on every row

If your rebuild exists for safety or honesty reasons, you will be tempted to caveat
everything. Resist proportionally.

One project put three-sentence epistemic caveats on every row of every screen — all of
them true and worth saying — until the whole app read like a disclaimer while the
incumbent said nothing and looked clean.

**Rule:** state the posture prominently in **one** place. On individual surfaces use the
shortest true label; put nuance behind a tap. Budget: **one line per control.**

### 6.4 UX is not a finishing pass

"Features now, polish later" does not work. By the time you notice, the navigation is
wrong and the mega-screens exist, and fixing it is a UI rewrite rather than a tidy-up.

---

## 7. Testing rules

### 7.1 A suite you authored both halves of proves only internal consistency

The sharpest lesson available. A unit bug survived **509 passing tests** because the wrong
value was written into the builder, the parser, the fixture *and* the fake device. Every
component agreed with every other. The only party that disagreed was the real hardware,
and it wasn't in the suite.

**Rule:** every protocol feature needs at least one value from **outside** your codebase —
a capture, the reference's own vectors, a device. If you can't get one, say so in the test
and treat the feature as unverified.

### 7.2 Assert ranges, not just examples

`assertEquals(910_525_000L, freq)` passed forever. `assertTrue(freq in 300_000..2_500_000)`
would have failed on day one.

Examples confirm what you already believe. Constraints catch what you didn't think of.

### 7.3 A suite of "asserts nothing happened" needs a positive control

If correctness means *declining* to answer, most of your tests pass when the feature does
nothing at all. Pin the case where it **must** answer.

### 7.4 Test the sender, not just the parser

A parser tested against captured frames proves nothing about the builder. Both halves of
one codebase can agree with each other and disagree with the world.

### 7.5 Green is not "works"

"Build succeeded, N tests passed" means the code compiles and agrees with itself. Nothing
more. Say it that way in status reports, out loud, every time.

---

## 8. Process

### 8.1 Count the right thing

Whatever you count, you will maximise. A feature tally goes up every session and measures
nothing a user cares about.

**Track weekly instead:**
- screens driven on real hardware
- tasks completable end to end
- tap counts vs the reference

Feature coverage belongs in a footnote.

### 8.2 Run it on a cadence

Weekly, minimum, on real hardware. Most defect classes in this genre only appear on
contact with the real thing — and a dozen appearing at once late on reads as "this app is
bad," where one a week reads as "normal progress."

### 8.3 A signal nobody reads is not a signal

Either watch CI or delete it. A permanently-red check trains you to ignore the dashboard,
and then a genuinely important failure hides in it for weeks.

### 8.4 Don't let the docs overclaim

Overclaiming READMEs and changelogs mislead **you**, three months later, about what's
actually done. Version claims especially: verify a feature is in the tagged build before
the changelog says it is.

---

## 9. Kill criteria — write these before you start

You will not make this judgement fairly once you're invested. Decide now.

Stop, or fall back to "a tool for me," if:

- After the shell phase, your most common task takes **more taps** than the incumbent's.
- You cannot state the charter sentence from memory.
- You're building features the incumbent doesn't have, and can't tie them to the defect list.
- The defect list has been fixed and you're still going.
- You reach for the app and open the incumbent instead.

That last one is the honest test, and it is the one that ended the project behind this
playbook — several months after it first became true.

---

## 10. Pre-flight checklist

Copy into the new repo's `README` and don't write code until every box is ticked.

```
[ ] Used the incumbent daily for a week
[ ] Five things people like about it, written down
[ ] Defect list, each item independently verifiable
[ ] Charter sentence written, ending "and otherwise indistinguishable"
[ ] Inventoried what open-source references exist
[ ] ONE reference named in the charter; changing it later = new project
[ ] Audience decided: tool-for-me | replacement-for-others
[ ] Screen inventory captured (screenshots, numbered)
[ ] Navigation graph drawn  ← from the SAME reference as the feature list
[ ] Ten task traces with tap counts
[ ] Incumbent's issue tracker read; do-not-copy list written
[ ] Kill criteria written down
[ ] If trust is the differentiator: §0.5 artefact list scheduled, not deferred
```

---

## 11. The shortest version

1. They already solved the UX. **Copy it. You are not designing.**
2. Your improvements are invisible, so their UX cost must be **zero**.
   Trust is what you're selling; ease of use is what gets you the chance to sell it.
   A tool nobody uses protects nobody.
3. Copy the **navigation**, not the feature list.
4. Prefer a source-available reference over a popular one — and pick **one**.
   A feature list without its navigation graph is half an artefact.
5. Never ask the user what the system already knows.
6. Pin at least one test value from **outside** your own code.
7. Count screens **driven**, not screens **written**.
8. Decide in advance what would make you stop.
