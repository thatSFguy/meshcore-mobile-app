# v1 Scope


> **Scope policy update (2026-08-01).** The mainstream MeshCore Android app
> (`com.liamcottle.meshcore.android`) is now the **floor** for this app's feature set:
> anything it does that we don't is a gap to close unless explicitly ruled out. See
> **[`PARITY.md`](PARITY.md)** for the full surface-by-surface matrix, the out-of-scope
> list (billing, crash telemetry, server-mediated features), and the places where we
> deliberately keep our own handling for security reasons. Where this document and
> PARITY.md disagree, PARITY.md wins.

Derived by pruning the MeshCore Open feature inventory (~26 screens) down to a deliberate
v1, then implementing it natively (KMP) on the reticulum-mobile-app foundation. Decisions
below are locked for v1; "Deferred" items are post-v1, "Cut" items are not planned.

## In v1

**Connection**
- Unified **"Add node"** flow over **BLE (Nordic UART Service)** and **USB serial** (COBS) —
  auto-detect transport where possible, saved-node list, event-driven reconnect. (Reuses the
  reticulum-mobile-app transport layer nearly verbatim — same transports.)
- **Per-transport enable toggles** (Settings → Connection → Transports), same as the
  reticulum app: a disabled transport is never started, so it never scans, connects, or
  feeds bytes to a parser — the runtime surface is only the paths you actually use.
- **TCP transport is OFF by default, behind a feature toggle.** Enabling it requires a
  one-time **stern warning + confirmation**: the link is *unencrypted and unauthenticated* —
  message text and the repeater login password cross the network in the clear, and anyone
  who can reach `host:port` can drive your radio. Only for reaching a WiFi/Ethernet MeshCore
  node on a trusted network; never over untrusted WiFi or the open internet. While enabled,
  the connection status should keep flagging the link as unencrypted.

**Messaging**
- **Direct messages** — contact list + 1:1 conversation threads.
- **Channels (group)** — channel list + group conversation threads, 16-byte PSK handling,
  channel add/edit, and **community QR join** (community secret → derived channel PSKs).

**Contacts / nodes**
- Contact list + **contact detail sheet** (pubkey, QR, rename, add/remove, reset path).

**Map**
- **In-app map** of node positions (advertised GPS). ⚠️ This is the only feature that makes
  **outbound HTTP** (map tiles). Mitigate: offline-first / privacy-conscious tile source,
  lazy load, basic tile cache. Hop-path overlays landed 2026-08-01 (PARITY §9).
  Line-of-sight is CUT, not deferred — see below.

**Repeater / room administration**
- **Login** (password → keystore, never plaintext prefs), **raw CLI** to a repeater, and the
  **settings editor**. Highest-surface piece in v1 — handle the login-password path and the
  `set prv.key` CLI carefully (redact from any diagnostics log; see MESHCORE_PROTOCOL §12).

**Settings**
- **Device settings** — radio params (freq/BW/SF/CR/TX power), identity, advertised name,
  advertised GPS.
- **App settings** — theme + a small, trimmed preference set.
- **One diagnostics log** (merge MeshCore Open's app-log + BLE-frame-log into a single,
  redaction-aware, off-by-default log).

## Deferred (post-v1)

- Telemetry (contact battery/sensor, Cayenne LPP)
- Companion-radio RF stats
- Neighbors (one-hop view) and a separate Discovery browser (fold discovery into contacts)
- Line-of-sight terrain map — **cut for good (2026-08-01)**: dedicated tools do RF
  coverage properly and the author maintains one. (Path-trace overlays, listed here
  originally, shipped 2026-08-01.)
- Dedicated map-tile-cache management screen (basic caching still ships with the map)

## Cut (not planned)

- Chrome-required / web gate (native app, N/A)
- On-device LLM translation (the ~31 MB llama.cpp bloat)
- GIF picker / remote media
- LXST-style voice/telephony, remote monitoring / situational-awareness dashboards

## Security carry-over

The v1 build must not repeat the MeshCore Open findings (see MESHCORE_PROTOCOL.md §12):
verify advert Ed25519 signatures, never trust channel sender names, keystore for all
secrets (login passwords, channel PSKs, community secrets, identity key), guard every
frame parse, warn that the TCP transport is plaintext, and present channels as obfuscated
(AES-ECB + 2-byte MAC) rather than secure.
