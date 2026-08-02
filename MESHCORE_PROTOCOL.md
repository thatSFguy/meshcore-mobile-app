# MeshCore Companion Protocol — Implementation Spec

A distilled reference for implementing a MeshCore **companion client** (phone ↔ radio)
and for decoding the small amount of **over-the-air** packet structure a client needs
(channel messages and adverts it sniffs via the radio's RX log).

**Provenance & status.** Reverse-engineered from the MeshCore Open client
(`meshcore_open`, Flutter) during a security review, cross-checked in places against the
MeshCore firmware wire notes and `michaelhart/meshcore-decoder`. Byte layouts here match
that client's frame builders/parsers. Treat command/response/push **code values** and
fixed layouts as reliable; treat exact **over-the-air raw-packet framing** (§7) as
"validate against firmware + a live device before trusting." Where a field's meaning is
inferred, it is marked _(inferred)_.

Endianness is **little-endian** for all multi-byte integers unless stated. Strings are
UTF-8; fixed-width name fields are null-padded/`\0`-terminated ("cstring").

---

## 1. Scope

Two distinct byte formats a client deals with:

1. **Companion frames** (§3–§6) — the request/response/push protocol spoken directly to
   the attached radio over BLE/TCP/USB. This is the bulk of the client.
2. **Over-the-air packets** (§7–§8) — the mesh packet format. A companion client mostly
   does *not* build these (the radio does), but it must parse a few that the radio hands
   up verbatim through the RX log push (`PUSH_CODE_LOG_RX_DATA`): group-text (channel)
   packets it decrypts itself, and adverts.

---

## 2. Transports & framing

All three transports carry the **same companion frames**; only the outer framing differs.

| Transport | Details |
|---|---|
| **BLE** | Nordic UART Service (NUS). Service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; **RX** char (client → radio) `6e400002-…`; **TX** char (radio → client, notify) `6e400003-…`. Each BLE write / notification carries one frame — no link framing (request MTU ≥ 175 so a max-size frame fits one write). |
| **TCP** | Raw socket to `host:port` (common default hint `192.168.40.10:5000`). Same start-byte + length framing as USB serial (below). |
| **USB serial** | 115200 baud, 8N1. `[start][len_lo][len_hi][payload]` — start `0x3C` ('<') client → radio, `0x3E` ('>') radio → client; len = u16 LE payload length ≤ 172. *(Corrected 2026-07-31: an earlier draft said "COBS-framed"; the reference client's `usb_serial_frame_codec.dart` — used for **both** USB and TCP and validated against firmware — uses this start-byte + length framing, not COBS.)* |

**BLE scan name prefixes** (advertised name starts with one of):
`MeshCore-`, `Whisper-`, `WisCore-`, `Seeed`, `Lilygo`, `HT-`, `LowMesh_MC_`.

**Protocol constants**
```
MAX_FRAME_SIZE          = 172   // max companion frame bytes
MAX_TEXT_PAYLOAD_BYTES  = 160   // firmware MAX_TEXT_LEN = 10 * CIPHER_BLOCK_SIZE
APP_PROTOCOL_VERSION    = 4
CIPHER_BLOCK_SIZE       = 16    // AES-128
CIPHER_MAC_SIZE         = 2     // truncated channel MAC (see §8)
PUB_KEY_SIZE            = 32
SIGNATURE_SIZE          = 64
MAX_PATH_SIZE           = 64
MAX_NAME_SIZE           = 32
```

---

## 3. Frame model

Byte `[0]` of every frame is a **code**:
- client → radio: a **command code** (§4),
- radio → client: a **response code** (reply to a command) or a **push code** (async;
  high bit set, `0x80+`) (§5, §6).

Responses are not tagged with a request id — correlate by ordering and by content
(e.g. ACK hashes, pubkey prefixes). A client typically keeps a small queue of
outstanding commands and matches `RESP_CODE_OK`/`RESP_CODE_ERR` to the oldest.

---

## 4. Command codes (client → radio)

| Code | Name | Purpose |
|---:|---|---|
| 1 | `CMD_APP_START` | Handshake / start session |
| 2 | `CMD_SEND_TXT_MSG` | Send a direct message (or CLI cmd, see txt_type) |
| 3 | `CMD_SEND_CHANNEL_TXT_MSG` | Send a channel (group) message |
| 4 | `CMD_GET_CONTACTS` | Request contact list (optional `since`) |
| 5 | `CMD_GET_DEVICE_TIME` | Read RTC |
| 6 | `CMD_SET_DEVICE_TIME` | Set RTC |
| 7 | `CMD_SEND_SELF_ADVERT` | Advertise self (flood flag) |
| 8 | `CMD_SET_ADVERT_NAME` | Set node display name |
| 9 | `CMD_ADD_UPDATE_CONTACT` | Add/update a contact (custom path, etc.) |
| 10 | `CMD_SYNC_NEXT_MESSAGE` | Pull next queued inbound message |
| 11 | `CMD_SET_RADIO_PARAMS` | Freq/BW/SF/CR (+clientRepeat v9+) |
| 12 | `CMD_SET_RADIO_TX_POWER` | TX power (dBm) |
| 13 | `CMD_RESET_PATH` | Reset stored path to a contact |
| 14 | `CMD_SET_ADVERT_LATLON` | Set advertised location |
| 15 | `CMD_REMOVE_CONTACT` | Delete a contact |
| 16 | `CMD_SHARE_CONTACT` | Zero-hop share of a contact |
| 17 | `CMD_EXPORT_CONTACT` | Export a contact (or self if empty key) |
| 18 | `CMD_IMPORT_CONTACT` | Import a contact from an advert blob |
| 19 | `CMD_REBOOT` | Reboot radio (payload `"reboot"`) |
| 20 | `CMD_GET_BATT_AND_STORAGE` | Battery + storage stats |
| 22 | `CMD_DEVICE_QUERY` | Query device (app protocol version) |
| 26 | `CMD_SEND_LOGIN` | Log in to a repeater/room (password) |
| 27 | `CMD_SEND_STATUS_REQ` | Request repeater status |
| 30 | `CMD_GET_CONTACT_BY_KEY` | Fetch one contact by pubkey |
| 31 | `CMD_GET_CHANNEL` | Read a channel slot |
| 32 | `CMD_SET_CHANNEL` | Write a channel slot (name + PSK) |
| 36 | `CMD_SEND_TRACE_PATH` | Path trace (tag/auth/flag) |
| 38 | `CMD_SET_OTHER_PARAMS` | Telemetry/advert-location/multi-ack policy |
| 39 | `CMD_SEND_TELEMETRY_REQ` | Request telemetry from a contact |
| 40 | `CMD_GET_CUSTOM_VAR` | Read custom vars |
| 41 | `CMD_SET_CUSTOM_VAR` | Write a custom var |
| 50 | `CMD_SEND_BINARY_REQ` | Binary request (telemetry/neighbors/etc.) |
| 54 | `CMD_SET_FLOOD_SCOPE` | Set flood scope/region tag |
| 55 | `CMD_SEND_CONTROL_DATA` | Control/discovery packet |
| 56 | `CMD_GET_STATS` | Core/radio/packet stats |
| 57 | `CMD_SEND_ANON_REQ` | Anonymous request (e.g. regions) |
| 58 | `CMD_SET_AUTO_ADD_CONFIG` | Auto-add contact policy |
| 59 | `CMD_GET_AUTO_ADD_CONFIG` | Read auto-add policy |
| 61 | `CMD_SET_PATH_HASH_MODE` | On-air path hash width (mode 0–3) |

---

## 5. Response codes (radio → command)

| Code | Name |
|---:|---|
| 0 | `RESP_CODE_OK` |
| 1 | `RESP_CODE_ERR` (byte `[1]` = error code, if present) |
| 2 | `RESP_CODE_CONTACTS_START` (`[1..4]` total count, v3+) |
| 3 | `RESP_CODE_CONTACT` (see §9) |
| 4 | `RESP_CODE_END_OF_CONTACTS` |
| 5 | `RESP_CODE_SELF_INFO` |
| 6 | `RESP_CODE_SENT` (`[1]`=is_flood, `[2..5]`=ack_hash u32, `[6..9]`=timeout_ms u32) |
| 7 | `RESP_CODE_CONTACT_MSG_RECV` (§9) |
| 9 | `RESP_CODE_CURR_TIME` |
| 10 | `RESP_CODE_NO_MORE_MESSAGES` |
| 11 | `RESP_CODE_EXPORT_CONTACT` (advert blob ≥ 98 bytes) |
| 12 | `RESP_CODE_BATT_AND_STORAGE` (`[1..2]`=mV u16, …) |
| 13 | `RESP_CODE_DEVICE_INFO` |
| 16 | `RESP_CODE_CONTACT_MSG_RECV_V3` (adds SNR + reserved; §9) |
| 17 | `RESP_CODE_CHANNEL_MSG_RECV_V3` |
| 18 | `RESP_CODE_CHANNEL_INFO` (§9) |
| 21 | `RESP_CODE_CUSTOM_VARS` |
| 24 | `RESP_CODE_STATS` (`[1]`=stats type: 0 core / 1 radio / 2 packets) |
| 25 | `RESP_CODE_AUTO_ADD_CONFIG` |

(`RESP_CODE_CHANNEL_MSG_RECV` = 8 exists alongside its V3 form 17.)

---

## 6. Push codes (radio → client, async; `0x80+`)

| Code | Name | Meaning |
|---:|---|---|
| 0x80 | `PUSH_CODE_ADVERT` | Known contact re-heard (pubkey only) |
| 0x81 | `PUSH_CODE_PATH_UPDATED` | `[1..32]` pubkey — path changed |
| 0x82 | `PUSH_CODE_SEND_CONFIRMED` | `[1..4]`=ack_hash u32, `[5..8]`=trip_ms u32 |
| 0x83 | `PUSH_CODE_MSG_WAITING` | Inbound message queued → `CMD_SYNC_NEXT_MESSAGE` |
| 0x85 | `PUSH_CODE_LOGIN_SUCCESS` | `[1]`=perm (fw sends 1/0), `[2..7]`=pubkey prefix |
| 0x86 | `PUSH_CODE_LOGIN_FAIL` | |
| 0x87 | `PUSH_CODE_STATUS_RESPONSE` | Repeater status |
| 0x88 | `PUSH_CODE_LOG_RX_DATA` | Raw RX packet (`[1]`=snr/4, `[2]`=rssi, then §7 packet) |
| 0x89 | `PUSH_CODE_TRACE_DATA` | Path-trace result |
| 0x8A | `PUSH_CODE_NEW_ADVERT` | New contact advert (same layout as `RESP_CODE_CONTACT`) |
| 0x8B | `PUSH_CODE_TELEMETRY_RESPONSE` | Telemetry (Cayenne LPP) |
| 0x8C | `PUSH_CODE_BINARY_RESPONSE` | Response to `CMD_SEND_BINARY_REQ` |
| 0x8E | `PUSH_CODE_CONTROL_DATA` | Discovery/control response |

---

## 7. Key companion frame layouts

`u8`/`u16`/`u32`/`i32` = little-endian; `[n]` = n bytes; `cstr(n)` = n-byte null-padded
UTF-8; `text…\0` = UTF-8 text + trailing `\0`.

```
CMD_APP_START (1)
  [1] app_ver=1 | [6] reserved | app_name…\0            // e.g. "MeshCoreOpen"

CMD_DEVICE_QUERY (22)
  [1] app_protocol_version (=4)

CMD_SEND_TXT_MSG (2)        // direct message; also CLI when txt_type=1
  [1] txt_type | [1] attempt | u32 timestamp | [6] dest_pubkey_prefix | text…\0
      txt_type: 0=plain, 1=cli_data, 2=signed

CMD_SEND_CHANNEL_TXT_MSG (3)
  [1] txt_type | [1] channel_idx | u32 timestamp | text…\0     // text = "name: msg"

CMD_SEND_LOGIN (26)         // repeater/room login — password is CLEARTEXT on the wire
  [32] recipient_pubkey | password…\0

CMD_GET_CONTACT_BY_KEY (30)     [32] pubkey
CMD_REMOVE_CONTACT (15)         [32] pubkey
CMD_RESET_PATH (13)             [32] pubkey
CMD_EXPORT_CONTACT (17)         [32] pubkey  (empty = export self)
CMD_SHARE_CONTACT (16)          [32] pubkey
CMD_GET_CHANNEL (31)            [1] channel_idx

CMD_SET_CHANNEL (32)
  [1] channel_idx | cstr(32) name | [16] psk

CMD_ADD_UPDATE_CONTACT (9)      // e.g. set a custom path
  [32] pubkey | [1] type | [1] flags | [1] path_len | [64] path (zero-pad) |
  cstr(32) name | u32 timestamp | [ i32 lat*1e6 | i32 lon*1e6 | (u32 lastmod) ]?

CMD_SET_RADIO_PARAMS (11)
  u32 freq_hz | u32 bw_hz | [1] sf(5..12) | [1] cr(5..8) | [1] client_repeat?(v9+)
CMD_SET_RADIO_TX_POWER (12)     [1] power_dbm
CMD_SET_ADVERT_LATLON (14)      i32 lat*1e6 | i32 lon*1e6
CMD_SET_ADVERT_NAME (8)         name (≤31 bytes)
CMD_SEND_SELF_ADVERT (7)        [1] flood(0/1)
CMD_SET_PATH_HASH_MODE (61)     [1] 0 | [1] mode(0..3)   // hop-hash width = mode+1 bytes
CMD_SET_FLOOD_SCOPE (54)        [1] 0 [| [16] scope]     // scope = SHA256("#region")[:16]; omit=reset

CMD_SEND_TRACE_PATH (36)        u32 tag | u32 auth | [1] flag | payload
      flag    = hop-hash width, encoded: 1 byte→0, 2 bytes→1, ≥3 bytes→2
                (the receiver derives width back as `1 << (flag & 0x03)`)
      payload = the ROUTE to trace, hop hashes in path order. NOT optional:
                a trace with no route is answered with RESP_CODE_ERR.
CMD_SEND_TELEMETRY_REQ (39)     [3] reserved | [32] pubkey?
CMD_SEND_BINARY_REQ (50)        [32] pubkey | payload      // payload[0]=req_type (see §11)
CMD_SEND_ANON_REQ (57)
  [32] pubkey | [1] req_type | [1] enc_path_len | reply_path
      enc_path_len = ((hash_width-1) << 6) | (hop_count & 0x3F)
      reply_path   = the route the ANSWER takes, reversed hop-by-hop
      req_type 0x01 = regions. Reply arrives as PUSH_CODE_BINARY_RESPONSE
        [1] reserved | u32 tag (= the RESP_CODE_SENT ack hash) | body
        body = [4] header | comma-separated NUL-padded UTF-8 names
        A node with NO named regions answers with a single '*' (0x2a) —
        that is an answer, not silence. Verified 2026-08-01:
          TX 39 <pubkey> 01 40                    // width 2, 0 hops
          RX 06 00 08896e6a 24090000              // SENT, est 2340 ms
          RX 8c 00 08896e6a 008c6e6a 2a 00 00 …   // body '*'
CMD_SEND_CONTROL_DATA (55)      payload   // discovery: [ (0x8<<4)|prefixOnly ][ type_mask ][ u32 tag ][ u32 since ]
```

### Path trace in detail (`CMD_SEND_TRACE_PATH`, corrected 2026-08-01)

The one-line summary above used to read `[1] flag | payload?`, which is
true and useless: it names the fields without saying what goes in them.
Reading it alone produces a trace the radio accepts and no node answers.
The details below are from live captures against a companion radio
(Galaxy A42 + MeshCore-Blue, 2-byte hop hashes), cross-checked against
the reference client's `lib/screens/path_trace_map.dart`.

**`flag` carries the hop-hash width.** It is not a bitfield of options.
The encoding is `1 byte→0, 2 bytes→1, ≥3 bytes→2`, and the receiving
side derives the width back out as `1 << (flag & 0x03)`. Sending a
hardcoded `0` on a 2-byte mesh produces a packet the radio will accept,
transmit, and never get an answer to.

**`payload` is the route, and it is required.** It is the hop hashes of
the path being traced, `width` bytes each, in path order. The reference
client sends a single `0x00` when it has no route; a companion radio
answers that with `RESP_CODE_ERR`. There is nothing to trace on a
direct contact — no intermediate node exists to report — so a client
should refuse locally rather than spend airtime being told no.

**Path direction is genuinely ambiguous.** The reference client exposes
`reversePathAround` and `flipPathAround` as *user-facing toggles*
rather than committing to one order, which is itself the finding: try
both and use whichever answers.

**`auth` is 0** in every observed request.

Captured exchange — a 2-hop route (`b389` → `c985`), accepted:
```
TX  24 9e6ba8bf 00000000 01 b3 89 c9 85
RX  06 00 9e6ba8bf de0e0000        // RESP_CODE_SENT, airtime estimate 3806 ms
```
The same request with no route, refused:
```
TX  24 5e32afbf 00000000 01 00
RX  RESP_CODE_ERR
```

**Timing.** `RESP_CODE_SENT` carries the radio's own airtime estimate
for the round trip (u32 ms, `0x0ede` = 3806 above). Wait on that plus
grace rather than a fixed timeout — a fixed one makes a dead trace and
a slow one indistinguishable.

**Resolved 2026-08-01: it works.** The earlier silence was the route,
not the protocol. Traced along a one-hop route through a node heard
seconds earlier, the reply came back in about a second:

```
TX 24 4c0cf7bf 00000000 01 b3 89                    // 1 hop, width 2
RX 06 00 4c0cf7bf 900a0000                          // SENT, est 2704 ms
RX 89 00 02 01 4c0cf7bf 00000000 b3 89 2b 25        // TRACE_DATA
```

Reply layout (`PUSH_CODE_TRACE_DATA`): `[1] reserved | [1] path_len |
[1] flags | u32 tag | u32 auth | path | per-hop SNR`, SNR in quarter-dB
signed (`0x2b` = 10.75 dB, `0x25` = 9.25 dB). The tag echoes the
request's.

The earlier failures were against a route whose far end had last been
heard 24 hours before. A trace has to traverse the whole route and
return, so one dead node anywhere along it yields exactly the silence
observed — which is why the client should say "check every node on this
route has been heard recently" rather than implying a fault.

---

## 8. Contact, message & channel-info layouts (radio → client)

### Contact (`RESP_CODE_CONTACT` = 3, and `PUSH_CODE_NEW_ADVERT` = 0x8A)
Fixed 148-byte record (offsets from byte 0 = code):
```
[1..32]   pubkey (32)
[33]      type            // 1=chat, 2=repeater, 3=room, 4=sensor
[34]      flags           // bit0 favorite, bit1 tele_base(+battery), bit2 tele_loc, bit3 tele_env
[35]      path_len
[36..99]  path (64, zero-padded)
[100..131] name  cstr(32)
[132..135] timestamp  u32
[136..139] lat  i32 (/1e6)
[140..143] lon  i32 (/1e6)
[144..147] last_modified u32
```
Validation: reject all-zero (or mostly-zero, >16/32) pubkeys and all-non-printable names.

### Direct message (`RESP_CODE_CONTACT_MSG_RECV` = 7 / `…_V3` = 16)
```
[0]      code
(v3 only) [1..3] snr + reserved (skip 3)
[+0..5]  sender pubkey prefix (6)
[+6]     path_len (skip)
[+7]     txt_type
[+8..11] timestamp u32
[+12..15] signature (4)   // only if txt_type indicates signed ((type>>2)==2 or type==2)
text…\0
```

### Channel message (`RESP_CODE_CHANNEL_MSG_RECV` = 8 / `…_V3` = 17)
Text body is `"<sender_name>: <message>"` — **the sender name is unauthenticated**
(see §12). channel_idx identifies the slot.

### Channel info (`RESP_CODE_CHANNEL_INFO` = 18)
```
[1]      channel_idx
[2..33]  name cstr(32)
[34..49] psk (16)
```

---

## 9. Over-the-air packet (inside `PUSH_CODE_LOG_RX_DATA`) — _validate against firmware_

After the push header (`[0]=0x88, [1]=snr/4, [2]=rssi`), the raw packet is:
```
[1] header   = (payload_ver << 6) | (payload_type << 2) | route_type
                 route_type: bits0-1     (flood/direct transport → 4 extra bytes follow)
                 payload_type: bits2-5   (§10)
                 payload_ver:  bits6-7
[4] transport bytes   // present only when route_type is flood or direct
[1] path_len_enc     = ((hash_width-1) << 6) | (hop_count & 0x3F)
[..] path            = hop_count * hash_width bytes  (each hop = hash_width-byte prefix of a pubkey)
[..] payload         // interpretation per payload_type
```

**This is the only place the full route appears.** The companion frames for a received
message (§8) carry `path_len` and nothing more — a hop COUNT — so "which repeaters
carried this" is answerable only from the RX log. The path is in TRAVEL order: hop 0 is
the repeater nearest the SENDER, the last hop is the one that reached this node. ⚠ It is
therefore the reverse of a stored out-path; reverse it hop-by-hop before pinning it as a
route to reply on.

Correlating an RX-log packet with the message it carried:
- **Group text is exact** — the client decrypts the GRP_TXT payload itself, so the packet
  and the message are the same object.
- **Direct text must be inferred** — a TXT_MSG payload is encrypted to the recipient's
  identity key, so the raw packet and the decrypted message arrive separately. They share
  the sender's key prefix (via `src_hash` below) and the hop count. Match on both, within
  a time window, and only when exactly one packet fits.

### Payload types
```
0x00 REQ         0x04 ADVERT        0x08 PATH        0x0B CONTROL
0x01 RESPONSE    0x05 GRP_TXT       0x09 TRACE       0x0F RAW_CUSTOM
0x02 TXT_MSG     0x06 GRP_DATA      0x0A MULTIPART
0x03 ACK         0x07 ANON_REQ
```

### ADVERT payload (0x04) — signed node identity
```
[32] pub_key | u32 timestamp | [64] signature | app_data
app_data = [1] flags [ i32 lat | i32 lon ]? [ name… ]?
           flags: bits0-3 type; 0x10 has_location; 0x80 has_name
```
**Signature = Ed25519 over `pub_key ‖ timestamp ‖ app_data`** — i.e. the whole payload
with the 64-byte signature spliced out. Verify with `pub_key` as the key. (Confirmed
against `michaelhart/meshcore-decoder`.) **A client MUST verify this** before trusting an
advert's name/type/location — an unsigned/forged advert otherwise spoofs identity/GPS.

### TXT_MSG payload (0x02) — direct message (NOT decryptable by a companion client)
```
[1]  dest_hash        // first byte of the RECIPIENT's public key
[1]  src_hash         // first byte of the SENDER's public key
[2]  mac
[..] ciphertext       // encrypted to the recipient's identity key
```
The prefix layout is from the reference client's own payload-type table ("prefixed with
dest/src hashes, MAC"), which applies equally to REQ (0x00), RESPONSE (0x01) and PATH
(0x08). A companion app never holds the identity key, so the body is opaque to it — but
`src_hash` is enough to narrow which sender a heard packet belongs to. One byte: it
narrows, it never identifies.

### GRP_TXT payload (0x05) — channel message (see §10 to decrypt)
```
[1] channel_hash        // = SHA256(psk)[0]
encrypted…
```

---

## 10. Channel cryptography

Channel/group messages use a 16-byte pre-shared key (PSK).

**Channel identification.** `channel_hash = SHA256(psk)[0]` (one byte). Multiple channels
can collide on this — try every configured channel whose hash matches, not just the first.

**Encrypted blob layout** (`encrypted` after the channel_hash byte):
```
[2]  mac        = HMAC_SHA256(key32, ciphertext)[0..1]     // only 2 bytes checked
[..] ciphertext = AES-128-ECB( key16 ) over 16-byte blocks
       key32 = psk zero-padded/truncated to 32 bytes  (HMAC key)
       key16 = psk[0..15]                              (AES key)
```
**Plaintext** (after ECB decrypt):
```
u32 timestamp | [1] txt_type | text cstr    // text = "<name>: <message>"; drop if (txt_type>>2)!=0
```

> ⚠️ **Protocol-inherent weaknesses (cannot be fixed without breaking interop):**
> AES-**ECB** (identical plaintext blocks → identical ciphertext; block splicing) and a
> **2-byte MAC** (~1-in-65 536 forgery). Encryption is done by firmware; a companion
> client only decrypts. Do not present channel messages as authenticated.

**PSK derivation**
```
Public channel PSK (well-known, world-readable):  8b3387e9c5cdea6ac9e5edbaa115cd72
Hashtag channel PSK:   SHA256("#" + name)[0..15]           // no secret — enumerable
Community public PSK:  HMAC_SHA256(K, "channel:v1:__public__")[0..15]
Community hashtag PSK:  HMAC_SHA256(K, "channel:v1:" + normalize(name))[0..15]
   normalize = strip leading '#', lowercase, trim
Community ID (public):  SHA256("community:v1" ‖ K)          // K = 32-byte community secret
```
`__public__`/hashtag community channels are opaque to non-members (need `K`); plain
hashtag channels are **obfuscation only** (anyone can derive the key from the name).

---

## 11. Misc encodings

**Radio params.** freq_hz 300 000–2 500 000 (×1000 for MHz? — firmware uses Hz here),
bw_hz 7 000–500 000, sf 5–12, cr 5–8.

**Binary request types** (`CMD_SEND_BINARY_REQ` payload `[0]`):
`0x01` get_status, `0x02` keep_alive, `0x03` get_telemetry, `0x05` get_access_list,
`0x06` get_neighbors. Telemetry payload: `[0x03,0,0,0,0]` (byte1 = inverse permission mask).

**Control/discovery** (`CMD_SEND_CONTROL_DATA`): subtypes `0x08` DISCOVER_REQ /
`0x09` DISCOVER_RESP; discover payload `[(0x08<<4)|prefix_only][type_mask][u32 tag][u32 since]`.

**Auto-add flags** (`CMD_SET_AUTO_ADD_CONFIG`): `0x01` overwrite-oldest, `0x02` chat,
`0x04` repeater, `0x08` room, `0x10` sensor.

**LoRa airtime / ACK timeout** — Semtech SX127x airtime formula; direct-path timeout
`500ms + (airtime*6 + 250ms)*(hops+1)`, flood `500ms + 16*airtime`. Used to time out ACKs.

**Contact-share URIs (QR codes).** Two forms exist in the wild; a client should emit the
first and accept both:

1. **Contact card** — what the mainstream MeshCore app emits and scans:
   ```
   meshcore://contact/add?name=<pct-encoded UTF-8>&public_key=<64 hex, UPPER>&type=<adv type>
   ```
   Spaces are `%20` (not `+`); `type` matches the ADVERT type byte (`1` chat, `2` repeater,
   `3` room, `4` sensor). Verified byte-for-byte against a QR that app produced.
2. **Advert blob** — `meshcore://<hex>`, the exported advert payload. Used by MeshCore Open
   and by early versions of this client. Import with `CMD_IMPORT_CONTACT`.

The two differ in what they prove. Form 2 is the signed advert, so the radio verifies it on
import. **Form 1 is unsigned** — name, key and type are plain query parameters anyone can
mint — so it cannot be imported through the verify path; it becomes a contact only via
`CMD_ADD_UPDATE_CONTACT` (path unknown, so flood until a route is learned), and the client
must ask the user to confirm the key rather than adding it silently. Treat the name as
display text in both forms; only the public key identifies anyone.

Note also that QR codes rendered by dark-mode apps are **inverted** (light modules on a dark
field). ZXing rejects those unless `DecodeHintType.ALSO_INVERTED` is set — worth doing, or
half the codes in circulation won't scan.

---

## 12. Identity & security notes for the client

**Identity.** Ed25519. Public key 32 bytes. MeshCore uses an **expanded 64-byte private
key**: `SHA512(seed)` with standard clamping `h[0]&=248; h[31]&=63; h[31]|=64` (keeps the
scalar in the large subgroup; required by firmware repeater key validation). Generate the
seed with a CSPRNG. A vanity-prefix search (regenerate until `pubkey` matches a hex
prefix) is supported.

**Things the client is responsible for (learned the hard way in the MeshCore Open audit):**
- **Verify advert Ed25519 signatures** (§9) before importing/updating a contact. Skipping
  this = identity/GPS spoofing.
- **Never trust the channel sender name** (§8) for identity, contact-record mutation, or
  self-echo suppression — it is attacker-controllable.
- **Don't infer delivery from malformed frames** — only mark sent/delivered on a
  well-formed `RESP_CODE_SENT` / `PUSH_CODE_SEND_CONFIRMED`.
- **Guard every parse** — a short/truncated frame from a hostile peer must not crash the
  RX path.
- **Store secrets in the platform keystore/keychain**, not plaintext prefs: repeater
  login passwords, channel PSKs, community secrets, and the device identity key.
- **TCP transport is plaintext & unauthenticated** — the `CMD_SEND_LOGIN` password and all
  message text cross the wire in the clear. Warn the user; prefer BLE/USB on untrusted
  networks.
- **Channel crypto is weak by protocol** (ECB + 2-byte MAC) — present channels as
  obfuscated, not secure.

---

## 13. Suggested module boundary (for a KMP client)

Mirror the `reticulum-mobile-app` split: keep the transport layer (BLE NUS, TCP, USB
serial, reconnect supervisors, foreground service) and put everything above in
`commonMain`:

```
transport/     BLE-NUS · TCP · USB-serial · framing (COBS) · reconnect
protocol/
  Frames.kt        command builders + response/push parsers (§4–§8)
  Codes.kt         command/response/push code enums (§4–§6)
  Advert.kt        advert parse + Ed25519 verify (§9)
  ChannelCrypto.kt PSK derivation + AES-ECB/MAC decrypt (§10)
  Identity.kt      Ed25519 keygen (expanded key), sign/verify (§12)
model/         Contact · Channel · Message · Telemetry
```

This is the seam where a MeshCore protocol layer drops in beside (or in place of) the
Reticulum/LXMF one.
