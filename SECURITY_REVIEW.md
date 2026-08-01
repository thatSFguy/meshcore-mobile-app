# Security review — 2026-07-31

Full-surface review of the MeshCore mobile app at commit `31354e6`, covering the protocol/crypto
layer (`shared/`) and the Android app layer (`androidApp/`). Two independent reviews were run, each
adversarial and each required to report negative results (what was checked and found sound) as well
as findings.

**Threat model.** A hostile node on the LoRa mesh — or a hostile radio / TCP peer — controls every
byte of every inbound frame: truncated, oversized, malformed, replayed, forged. Secondary threats: a
malicious app on the same device, someone with physical access to the phone, and network observers
on the (off-by-default) TCP path and the OSM tile fetches.

**Outcome: 24 findings. All critical/high/medium issues are fixed** in commit `003dc73`; the
remainder are accepted risks or documentation notes, listed below with the reasoning. Six regression
tests (`HardeningTest.kt`) pin the fixes.

---

## Fixed

### Protocol / crypto

| # | Sev | Finding | Fix |
|---|---|---|---|
| P1 | **Critical** (iOS) | `IosCryptoProvider.ed25519Verify` threw `NotImplementedError`. Reached on every heard advert inside the RX collector, which had no catch — and `NotImplementedError` is an `Error`, so `catch (Exception)` guards elsewhere would not have helped. **One advert from any node would permanently deafen the iOS app** until reconnect. | iOS Ed25519 stubs now **fail closed** (`verify` → `false`) instead of throwing, *and* the RX collector wraps frame handling in `try/catch (Throwable)` so no parse or platform failure can ever kill the receive path. |
| P2 | **High** | Every `PUSH_CODE_ADVERT` / `PATH_UPDATED` spawned an unbounded coroutine issuing a radio command, with no dedup or debounce. A replayed advert (no key needed) at line rate → coroutine/command amplification → OOM, or `commandMutex` starvation that times out every user action. | Per-node refresh **debounce (30 s)** behind a mutex, with the debounce map itself bounded. |
| P3 | **High** | `ContactsStart`/`CONTACT` and `NEW_ADVERT` grew the contact map without limit — a hostile link streaming 148-byte records exhausts the heap. | Both paths **capped** at the radio's reported `maxContacts` (hard ceiling 1024); overflow abandons the sync and logs. |
| P4 | Medium | `sendCliAndAwaitReply` accepted *any* DM from the target as the answer — including an ordinary chat message — because the waiter subscribes before the command is even transmitted. A chat message could be parsed as a settings value in the remote-settings form. | The predicate now requires `txtType == TXT_TYPE_CLI_DATA`. |
| P5 | Medium | DM sender attribution used `firstOrNull` over a 6-byte pubkey prefix: on collision the message (and any correlated CLI reply) was attributed to an arbitrary identity, silently. | Ambiguous prefixes now resolve to **no** contact rather than a guess. |
| P6 | Medium | `tryEmit` return values were ignored everywhere; under burst load inbound messages were silently dropped before persistence. | Dropped emissions are logged; message events go through a single `emitMeshEvent` chokepoint. |
| P7 | Medium/Low | `drainingQueue` was a plain `Boolean` written from two threads on `Dispatchers.Default`; a stale `true` would permanently stop queued-message pulls with no user-visible error. | Marked `@Volatile`. |
| P8 | Low | `BufferReader.ensure` had no negative/overflow guard — a future length-driven read could escape as a non-`TruncatedFrameException` into the RX collector. | `count < 0 || count > remaining` now raises `TruncatedFrameException`. |
| P9 | Low | `Advert.parse` with `has_location` set but < 8 bytes left silently skipped the coordinates and **read the location bytes as the node name**. | Rejects the advert; name is also length-capped. |
| P10 | Low | Attacker-supplied names (contact records, adverts, channel sender names) reached list rows, notifications and map labels with newlines and bidi overrides intact — classic RTL/homograph impersonation. | New `sanitizeDisplayName()` strips C0/C1 controls and bidi format chars and clamps length; applied at parse time to contacts, adverts and channel sender names. |
| P11 | Low/Info | Channel MAC comparison short-circuited on the first byte. | Constant-time compare. (No practical oracle here — 2-byte tag, local decrypt — done for hygiene.) |
| P12 | Info | Short/corrupt PSKs were silently zero-padded into a weak key. | `decrypt` rejects any PSK that isn't exactly 16 bytes. |

### Android app

| # | Sev | Finding | Fix |
|---|---|---|---|
| A1 | **Medium-High** | The repeater CLI console wrote raw commands **and replies** into the unencrypted `messages` table — including `password …`, `set guest.password …`, `set prv.key …`, and the replies to `get guest.password` / `get prv.key`. The redaction SCOPE.md requires covered only the in-memory diagnostics log, not the durable store. | Console rows are now passed through `DiagnosticsLog.redact` **before** insert, both outbound commands and CLI-typed replies. The clear text exists only in the outbound frame. |
| A2 | Medium | The GPX export's FileProvider authority was a broken string literal (`${'$'}{app.packageName}`), so the export threw `IllegalArgumentException` inside a coroutine with no catch — **crashing the app** from the Map menu. | Fixed the interpolation (and the same bug in the toast). The share path now works as designed. |
| A3 | Medium-Low | Message notifications rendered full body text with default visibility — readable on the lock screen. | `VISIBILITY_PRIVATE` with a generic `setPublicVersion` ("New message"). |
| A4 | Low | Repeater passwords were sealed to the Keystore *before* the login was validated, so a wrong password was persisted. | Credentials are only stored after the node accepts them. |
| A5 | Low | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was declared but never used. | Permission removed. |
| A6 | Low | `ACCESS_FINE_LOCATION` was declared for pre-Android-12 BLE scanning but never requested at runtime, so scanning silently returned nothing on Android 8–11. | Now requested below API 31. |
| A7 | Low | The GPX export left a file containing every node's name, key and GPS in the cache indefinitely. | The export directory is cleared before each export. |
| A8 | Info | `KeystoreSecretVault` KDoc described a "plaintext-column fallback" that (correctly) does not exist. | Comments corrected — there is **no** plaintext fallback; if the Keystore is unavailable the secret is simply not stored. |
| A9 | Info | `registerReceiver` for USB-detach had no explicit export flag (safe today — it's a protected system broadcast). | Registered `RECEIVER_NOT_EXPORTED` explicitly. |

---

## Accepted risks (not fixed, with reasoning)

- ~~**Room database is not encrypted at rest**~~ — **FIXED (2026-07-31, follow-up).** The database
  is now SQLCipher-encrypted with a 32-byte random passphrase sealed by the Android Keystore.
  An existing plaintext database is converted in place via `sqlcipher_export`, and Settings → App
  states plainly whether encryption is active.
- ~~**OSM tile fetches disclose regions of interest**~~ — **ADDRESSED.** Settings → App and the Map
  ⋮ menu carry a *Load map tiles (network)* toggle; with it off the map still plots markers but
  fetches nothing, so the app makes no outbound HTTP at all.
- **Channel crypto is weak by protocol** — AES-ECB with a 2-byte MAC. Unfixable without breaking
  interop; the app labels channels "obfuscated, not secure" in the UI rather than implying privacy
  it cannot provide.
- **The TCP transport is plaintext and unauthenticated** — by protocol. Off by default, behind a
  one-time stern warning, and flagged in the UI for the whole time it's connected.
- **Per-packet SHA-256 fan-out** when matching channel hashes (P-Info): cheap at LoRa rates,
  worth caching if flooding ever becomes a concern.

## Follow-up hardening (2026-07-31, after the initial fixes)

**Database encryption at rest.** SQLCipher via `SupportOpenHelperFactory`, keyed by a 32-byte
random passphrase generated on first run and sealed by the Keystore vault. Migration of an existing
plaintext database was written to be **strictly non-destructive**, because the sibling repo lost a
database to exactly this class of bug:

- No `fallbackToDestructiveMigration*` anywhere in the builder. An unexpected schema version now
  fails loudly instead of silently deleting history.
- The encrypted copy is **verified before anything is destroyed** (it must open with our key and
  carry at least as many tables as the source).
- The swap goes original → `.plaintext-backup` → encrypted into place, and restores the backup if
  the rename fails. No delete-then-rename window where a crash loses the file.
- If the key is ever unrecoverable (Keystore invalidated), the encrypted file is **left untouched**
  and the app runs from a separate empty database rather than opening the real one destructively.
- If the SQLCipher native library can't load, that is treated exactly like a missing key.

Verified on-device: the pre-existing plaintext database migrated cleanly (8 tables, schema v4), the
file header is now random bytes rather than `SQLite format 3`, every conversation survived, and a
message received after the migration persisted normally.

## Verified sound

Both reviews independently confirmed, by reading the code rather than assuming:

- **Advert signature gating** — `Advert.parse` (the unverified variant) has no callers outside the
  protocol module and its tests. The only over-the-air path uses `parseVerified`; contact import
  verifies before the blob reaches the radio; the discovery inbox stores only verified adverts;
  self-advert echo is filtered. The signed-message construction matches the spec.
- **Channel sender names are never used for identity** — display and dedup only, never contact
  lookup or mutation.
- **Parse safety** — `BufferReader` bounds every read; `RawPacket`, `TracePath`, `StatusCodec`,
  `CayenneLpp`, `ResponseParser` and `SerialFrameDecoder` were each traced for unbounded
  allocation, infinite loops and escaping exceptions. `CayenneLpp` consumes ≥2 bytes per iteration
  and aborts on unknown types rather than misreading; `SerialFrameDecoder`'s buffer growth is
  bounded and it resyncs on garbage.
- **Delivery is never inferred** — only a well-formed `PUSH_CODE_SEND_CONFIRMED` marks a message
  delivered. `repeaterStatus` and `tracePath` correlate on echoed content (sender prefix, tag)
  rather than bare ordering.
- **Secrets** — everything sensitive goes through the Keystore vault (AES-256-GCM, non-extractable
  key in TEE/StrongBox, fresh IV, authenticated); only sealed base64 blobs reach SharedPreferences;
  admin and guest credentials use separate slots; channel PSKs are sealed before the DB write.
- **IPC surface** — only `MainActivity` is exported, with `MAIN`/`LAUNCHER` and **no** URI
  intent-filter (so `meshcore://` is reachable only from a QR scan or paste, never from another
  app); service and QR activity non-exported; FileProvider is non-exported, grant-based, and scoped
  to `cache/exports/` alone; the USB permission `PendingIntent` is package-scoped so no other app
  can forge the result.
- **Dependencies** — the resolved release classpath contains **zero** Play Services, Firebase, GMS,
  analytics or crash-reporting libraries, and no HTTP client at all; osmdroid's tile fetcher is the
  only network egress besides the transports.
- **SQL** — every DAO method is a parameterised `@Query`; no raw query surface.
- **Diagnostics log** — off by default, in-memory only, never written to a file or exported,
  redaction unit-tested.
- No WebView, no JavaScript, no dynamic code loading, no clipboard writes of secrets.

---

## Re-running this review

```bash
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest   # incl. HardeningTest
```

The review is a snapshot, not a guarantee. Re-run it after any change to the parsing layer, the
engine's event handling, or the secret-storage path.
