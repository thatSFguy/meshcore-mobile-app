# Reuse map — from `../reticulum-mobile-app`

File-by-file plan for lifting the foundation. **Copy** = take ~verbatim (rename package
`…reticulum…` → `…meshcore…`). **Adapt** = copy then rewire to MeshCore models/protocol.
**Skip** = Reticulum-specific, don't bring. **New** = no source, build from
`MESHCORE_PROTOCOL.md` / `SCOPE.md`.

Package root there: `io.github.thatsfguy.reticulum.*` (rename to `.meshcore.*`).
Method: **copy the pieces into this repo**, do not fork-and-delete.

---

## 1. Transport plumbing — the biggest win (same transports)

These move bytes and don't care about frame content.

| File (in reticulum-mobile-app) | Do | Note |
|---|---|---|
| `shared/…/platform/BleTransport.kt` (androidMain) | **Copy** | GATT connect to **NUS** RX/TX — MeshCore uses the same NUS UUIDs |
| `shared/…/platform/IosBleTransport.kt` (iosMain) | **Copy** | iOS CoreBluetooth NUS |
| `shared/…/platform/UsbSerialTransport.kt` + `platform/usbserial/UsbSerial.kt` | **Copy** | CDC-ACM / CP210x serial |
| `shared/…/transport/TcpSocket.kt` + `.android.kt` + `.ios.kt` | **Copy** | for the (off-by-default) TCP toggle |
| `shared/…/transport/TcpInterface.kt`, `KnownTcpNodes.kt` | **Adapt** | TCP node list; wire to MeshCore add-node |
| `shared/…/transport/ConnectionMemory.kt` | **Copy** | saved-node persistence + event-driven reconnect |
| `shared/…/transport/Transport.kt` | **Adapt** | transport interface — keep shape, MeshCore frames flow through it |
| `shared/…/transport/NusDemux.kt` | **Adapt** | reuse the BLE-NUS byte-stream reassembly; **replace the frame-delimiting** with MeshCore companion framing |
| `androidApp/…/platform/NodeDiscovery.kt` | **Adapt** | BLE scan → MeshCore scan-name prefixes (`MeshCore-`, `Whisper-`, …) |
| `shared/…/platform/BtClassicTransport.kt` | **Skip** | MeshCore radios are BLE (NUS), not BT-Classic SPP |
| `shared/…/transport/Kiss.kt`, `Hdlc.kt`, `RNodeInit.kt` | **Skip** | RNode KISS/HDLC framing → replaced by MeshCore companion frames + COBS (USB) |
| `shared/…/transport/AgnosticLoraRouter.kt`, `AgnosticLoraTunnel.kt`, `platform/AgnosticLoraBleTransport.kt` | **Skip** | Reticulum-over-LoRa routing |

**New here:** a `transport/CobsFraming` (USB) + `protocol` frame-delimiter to replace
Kiss/Hdlc (MeshCore framing per `MESHCORE_PROTOCOL.md` §2).

---

## 2. Crypto & identity

| File | Do | Note |
|---|---|---|
| `shared/…/crypto/CryptoProvider.kt` + `AndroidCryptoProvider.kt` + `IosCryptoProvider.kt` | **Copy** | Ed25519 sign/verify, AES, SHA-256, HMAC — MeshCore needs all of these |
| `shared/…/crypto/IdentityVault.kt` + `platform/KeychainIdentityVault.kt` (iOS) | **Copy** | Keystore/Keychain — use for identity **and** login passwords, channel PSKs, community secrets |
| `shared/…/crypto/IdentityArchive.kt` | **Adapt** | passphrase-encrypted identity backup (PBKDF2→HKDF→AES-CBC+HMAC) — good pattern for MeshCore key backup |
| `shared/…/crypto/ConstantTime.kt`, `PassphraseStrength.kt` | **Copy** | utilities |
| `shared/…/crypto/Identity.kt` | **Adapt → New** | Reticulum X25519+Ed25519; MeshCore identity is **Ed25519 with the expanded 64-byte key** (§12). Use as a structural template, implement MeshCore keygen/clamp |
| `shared/…/crypto/TokenCrypto.kt` | **Skip** | Reticulum link Token crypto |

**New:** `protocol/Identity` (MeshCore expanded-key Ed25519), `protocol/Advert` (Ed25519
advert verify), `protocol/ChannelCrypto` (PSK derivation + AES-128-ECB + 2-byte MAC).

---

## 3. Persistence, service, radio config

| File | Do | Note |
|---|---|---|
| `androidApp/…/service/ReticulumService.kt` | **Copy** | foreground service for persistent BLE/USB connection |
| `androidApp/…/MainActivity.kt`, `platform/PortraitCaptureActivity.kt` | **Copy** | app entry + QR capture |
| `androidApp/…/storage/ReticulumDatabase.kt` + `shared/…/platform/IosDatabase.kt` + `shared/…/sqldelight/**` | **Adapt** | SQLDelight scaffolding; **new schema** (Contact/Channel/Message not LXMF) |
| `shared/…/platform/RadioConfig.kt` | **Adapt** | freq/BW/SF/CR — MeshCore has the same radio params (`CMD_SET_RADIO_PARAMS`) |
| `shared/…/store/Models.kt` | **Adapt** | → MeshCore models |
| `shared/…/store/ReactionsJson.kt` | **Copy** | if keeping reactions |
| `shared/…/store/RrcModels.kt`, `store/AttachmentStore*.kt`, `codec/*` (Cbor/MessagePack/Bz2/OggOpus) | **Skip** | RRC, image attachments, Reticulum codecs, voice |

---

## 4. UI — Android (Jetpack Compose, `androidApp/…/ui/`)

| File | Do | Note |
|---|---|---|
| `ui/theme/Theme.kt`, `ui/screens/EmptyState.kt` | **Copy** | |
| `ui/ReticulumViewModel.kt` | **Adapt** | → `MeshCoreViewModel` — keep the state-holder structure |
| `ui/screens/MessagesScreen.kt` | **Adapt** | conversation list (generic messaging UI) |
| `ui/screens/NodesScreen.kt` | **Adapt** | → contacts / nodes |
| `ui/screens/DestinationDetailSheet.kt` | **Adapt** | → contact detail (pubkey, QR, rename, reset-path) |
| `ui/screens/SettingsScreen.kt` | **Adapt** | transports (+ **TCP toggle w/ warning**), identity, radio config |
| `ui/screens/NomadScreen.kt`, `MicronView.kt`, `RoomsScreen.kt`, `GraphScreen.kt` | **Skip** | NomadNet, RRC, Graph (Graph deferrable later) |

**New Compose:** channel list + channel chat, community-QR-join flow, repeater login/CLI/
settings, and the **node map** (net-new — reticulum-app has no map; this is the HTTP-tile
piece).

---

## 5. UI — iOS (SwiftUI, `iosApp/iosApp/`)

| File | Do | Note |
|---|---|---|
| `iOSApp.swift`, `ContentView.swift` | **Adapt** | app shell / tab host |
| `Tabs/MessagesView.swift`, `ConversationView.swift`, `MessageBubble.swift` | **Adapt** | messaging UI |
| `Tabs/NodesView.swift`, `Tabs/DestinationDetailSheet.swift` | **Adapt** | contacts / detail |
| `Tabs/SettingsView.swift` | **Adapt** | + TCP toggle/warning |
| `Tabs/QrScannerView.swift`, `KeyboardDismiss.swift` | **Copy** | |
| `Store/ReticulumStore.swift` | **Adapt** | → MeshCore store bridge |
| `Store/IosNotifications.swift`, `IosBleScanManager.swift`, `NetworkPathMonitor.swift` | **Copy** | iOS infra |
| `Tabs/NomadView.swift`, `MicronView.swift`, `RoomsView.swift`, `GraphView.swift` | **Skip** | |
| `Store/VoiceAudio.swift`, `ImageCompress.swift`, `PhaseThreePlaceholder.swift` | **Skip** | voice, attachments |

> Android-first is viable: get `:shared` protocol + transport + the Android screens working
> on real hardware, then mirror to SwiftUI.

---

## 6. Net-new (no reuse source)

- **`protocol/`** (KMP commonMain) — from `MESHCORE_PROTOCOL.md`: `Codes`, `Frames`
  (builders + parsers), `Advert` (parse + Ed25519 verify), `ChannelCrypto`, `Identity`.
- **Channels** — list, chat, PSK handling, community QR join (MeshCore-specific).
- **Repeater/room admin** — login (→ keystore) + CLI + settings editor.
- **Node map** — in-app map of advertised GPS (introduces map-tile HTTP; keep offline-first).

---

## Suggested order

1. `protocol/` module + tests (advert-sig round-trip, channel-decrypt vector).
2. Copy §1 transport plumbing; wire the new framing + `protocol/` to it → get frames
   flowing to a real radio (BLE first).
3. Copy §2 crypto/vault + §3 service/DB scaffolding.
4. Adapt §4 Android screens; prove DMs → then channels → then map → then repeater admin.
5. Mirror to §5 SwiftUI.
