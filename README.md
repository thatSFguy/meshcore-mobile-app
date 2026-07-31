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
| USB serial (COBS-framed, CDC-ACM/CP210x) | ✅ |
| Foreground service · notifications · reconnect · identity/QR | ✅ patterns |

What differs is the framed **companion protocol** spoken to the radio, plus the small bit
of over-the-air packet decoding a client needs (adverts, channel messages).

## Status

**Design stage.** Starting point is the protocol reference:

- **[`MESHCORE_PROTOCOL.md`](MESHCORE_PROTOCOL.md)** — the companion + over-the-air wire
  format (frames, command/response/push codes, advert + Ed25519 signature, channel
  crypto), distilled from a security review of MeshCore Open. This is the spec a Kotlin
  Multiplatform implementation builds from.

The `MESHCORE_PROTOCOL.md` §12 security notes capture the specific mistakes to *not* repeat
(verify advert signatures, never trust channel sender names, keystore for secrets, guard
every parse, TCP is plaintext).

## Non-goals

Maps, GIF pickers, on-device LLM translation, telemetry dashboards, remote monitoring — the
things that bloat the other clients. If you want those, fork it.
