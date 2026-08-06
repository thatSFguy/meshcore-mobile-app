# Security policy

This is an independent Kotlin Multiplatform MeshCore client. The
protocol layer parses attacker-controlled frames off the LoRa mesh and
handles identity, so a vulnerability here can affect anyone who
messages anyone using this client.

**Known and accepted protocol-level weaknesses** — please do not report
these as vulnerabilities, they are properties of MeshCore itself and are
documented in the app's own UI:

- **Channel crypto is obfuscation, not security**: AES-ECB with a
  2-byte MAC. Cannot be changed without breaking interop with every
  other MeshCore node.
- **The TCP transport is plaintext and unauthenticated.** It is off by
  default, behind a one-time warning, and flagged in the UI while
  connected.
- **Channel sender names are unauthenticated** display text.

What IS in scope: anything that breaks the app's own guarantees —
advert signature verification, secret storage, frame-parse safety,
data-at-rest encryption, or the redaction of secrets from logs. See
[SECURITY_REVIEW.md](SECURITY_REVIEW.md) for the current posture.

If you've found something, **please report it privately first.** A
public issue or PR puts every user at risk before a fix is shipped.

## Reporting a vulnerability

Three channels, in preference order:

1. **GitHub Private Vulnerability Reporting** —
   <https://github.com/thatSFguy/meshcore-mobile-app/security/advisories/new>
   Creates a private thread visible only to maintainers; you can
   collaborate on fixes there. Best for technical reports with PoC
   code.

2. **Email** — `rob@woodhousellc.com`
   PGP welcome but not required. Use the same body shape as the
   GitHub form below if you can; otherwise just describe the bug.

3. **Off-platform** — if you'd rather avoid the channels above, say so
   in a one-line email (#2) and we can agree on somewhere else.

## What to include

A useful report has:

- **Affected version(s)** — preferably the lowest version that
  reproduces. This client is young (first tagged release v0.1.0); if
  you're on an older build, please retest against current `main`
  before reporting.
- **Severity self-assessment** — one of:
  - **Critical** — RCE, key disclosure, ability to read other users'
    messages, ability to forge adverts (identity/GPS
    spoofing) or messages from other identities.
  - **High** — DoS that affects more than the vulnerable user (e.g.
    a transport node forced to drop traffic for unrelated peers),
    persistent state corruption, identity-hash leakage.
  - **Medium** — single-user DoS (process crash), local data
    leakage requiring physical / OS-level access, vulnerabilities
    that depend on a misconfiguration the user has to actively make.
  - **Low** — hardening / defense-in-depth concerns; unverified or
    speculative findings.
- **Reproducer** — minimum bytes / steps that trigger the bug.
  Test vectors live in `reference/test-vectors.json` if you want
  a starting fixture.
- **Suggested mitigation** — optional but appreciated.

## What I'll do

- Acknowledge receipt within **3 business days**. If you don't hear
  back, the email may have been filtered — please escalate via a
  different channel.
- Triage to a severity level within **7 days**.
- Targeted fix release timeline depending on severity:
  - **Critical** — patch and release within 7 days, immediately
    yank the affected versions from the GitHub release page.
  - **High** — patch and release within 30 days.
  - **Medium / Low** — fix in the next regular release cycle.
- Coordinate disclosure timing with you. The default is **90 days
  from acknowledgement** before any public mention; happy to extend
  if you need more time, or compress if a fix is already public.
- Publish a security advisory at disclosure, crediting you (or
  acknowledging your preference for anonymity).

## What this project ISN'T responsible for

This is an independent client for the **MeshCore** protocol. It is not
affiliated with the official MeshCore app or with the MeshCore project.

**Vulnerabilities in the MeshCore protocol itself** — the channel
cipher, the companion frame format, the firmware — belong upstream at
<https://meshcore.co.uk/>, not here. Likewise bugs in any other MeshCore
client — this repo is only responsible for its own code. If you're not
sure which project a finding belongs to, file it here and I'll triage
and forward as appropriate.

## Hall of fame

Reporters who would like to be credited will be listed here after
each disclosure cycle. Anonymous-by-default — credit only with your
explicit say-so.

## See also

- **[MESHCORE_PROTOCOL.md](MESHCORE_PROTOCOL.md)** — the wire-format
  reference this client implements, including §12's list of the
  client-side mistakes this app exists to avoid. A weakness described
  there as protocol-inherent is upstream's, not this client's.
- **[SECURITY_REVIEW.md](SECURITY_REVIEW.md)** — the last full-surface
  review: findings, fixes, accepted risks, and what was verified sound.
- **`HardeningTest.kt`** — regression tests pinning the fixes from that
  review. A report that also breaks one of these is especially useful.
