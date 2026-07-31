# MeshCore Mobile App

*A minimal, native MeshCore client — off-grid encrypted messaging over LoRa, built the way
[reticulum-mobile-app](https://github.com/thatSFguy/reticulum-mobile-app) is: no servers,
no accounts, no Google Play Services, no analytics, smallest attack surface I can keep
secure and reason about.*

## Why this exists

The existing MeshCore clients are either the official cross-platform app (feature-rich,
web-tech) or [MeshCore Open](https://github.com/meshcore-dev) (Flutter, kitchen-sink).
Neither is a lean, native, security-first client in the mold of `reticulum-mobile-app`.
This fills that gap for the MeshCore network the same way that app filled it for Reticulum.

**Scope is deliberately small** — direct + channel messaging over a MeshCore radio, done
solidly, with a static attack surface. Not a Swiss-army knife.

## Approach

MeshCore and `reticulum-mobile-app` share the **same transport layer**, so this reuses that
app's foundation and swaps only the protocol:

| Transport | Shared |
|---|---|
| BLE Nordic UART Service (`6e400001-…`) | ✅ (identical to the RNode BLE path) |
| Direct TCP to `host:port` | ✅ |
| USB serial (start-byte + length framed, CDC-ACM/CP210x) | ✅ |
| Foreground service · notifications · reconnect · identity/QR | ✅ patterns |

What differs is the framed **companion protocol** spoken to the radio, plus the small bit
of over-the-air packet decoding a client needs (adverts, channel messages).

## Status

**v1 implemented (Android); iOS is a Phase-1 skeleton.** Kotlin Multiplatform:

- **`shared/`** — `protocol/` (frames, parsers, advert Ed25519 verify, channel crypto,
  identity), `engine/` (session/handshake/sync/messaging), `transport/` (BLE NUS, USB
  serial, TCP behind the off-by-default toggle) — all built from
  **[`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)** and unit-tested against a scripted
  fake radio (advert-signature round-trip, channel-decrypt vectors, malformed-frame
  guards).
- **`androidApp/`** — the full v1 UI: chats (DMs + channels), nodes + contact detail,
  osmdroid node map, repeater/room admin (login → keystore, CLI), settings (radio params,
  transports with the stern TCP plaintext warning, redaction-aware diagnostics log).
  `./gradlew :androidApp:assembleDebug` builds; `testDebugUnitTest` is green.
- **`iosApp/`** — SwiftUI skeleton (XcodeGen), see `iosApp/README.md` for the staged
  bring-up plan (needs a Mac).

The `MESHCORE_PROTOCOL.md` §12 security notes capture the specific mistakes to *not* repeat
(verify advert signatures, never trust channel sender names, keystore for secrets, guard
every parse, TCP is plaintext) — each is enforced in code, not just documented.

**Not yet validated against real hardware.** The protocol layer is built from the spec +
reference client; the first on-radio session may surface framing/ordering quirks.

## Scope

See **[`SCOPE.md`](SCOPE.md)** for the locked v1 feature set (pruned from MeshCore Open's
full inventory). In v1: connect (BLE/TCP/USB), direct + channel messaging (with community
QR join), node map, repeater/room admin, settings. **Non-goals:** on-device LLM
translation, GIF picker / remote media, LXST-style voice, remote-monitoring dashboards.

The in-app node map is the one feature that makes outbound HTTP (tile downloads);
everything else stays offline (mesh packets only).
