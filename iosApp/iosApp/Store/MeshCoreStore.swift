// The single ObservableObject bridging the Kotlin `Shared` framework's
// MeshCoreEngine to SwiftUI. Phase-1 skeleton: state mirrors are
// updated by polling the engine's StateFlow `.value` properties on a
// short main-actor timer — the sibling repo graduated from this to
// proper flow-collection wrappers in its Phase 2; do the same here once
// the app runs on a Mac.

import Foundation
import SwiftUI
import Shared

struct UiContact: Identifiable, Hashable {
    let id: String        // pubkey hex
    let name: String
    let type: Int32
    let latitude: Double?
    let longitude: Double?
}

struct UiChannel: Identifiable, Hashable {
    let id: Int32         // slot index
    let name: String
}

struct UiMessage: Identifiable, Hashable {
    let id = UUID()
    let senderName: String?
    let text: String
    let timestamp: Int64
    let outgoing: Bool
}

@MainActor
final class MeshCoreStore: ObservableObject {

    @Published var engineStateLabel: String = "Not connected"
    @Published var isReady: Bool = false
    @Published var isPlaintextLink: Bool = false
    @Published var selfName: String = ""
    @Published var selfKeyHex: String = ""
    @Published var contacts: [UiContact] = []
    @Published var channels: [UiChannel] = []
    /// Threads keyed "dm|<hex>" / "ch|<idx>" — in-memory in Phase 1.
    @Published var threads: [String: [UiMessage]] = [:]

    /// TCP transport gate — same off-by-default + stern warning as
    /// Android (SCOPE.md).
    @AppStorage("tcpEnabled") var tcpEnabled: Bool = false
    @AppStorage("tcpWarningAccepted") var tcpWarningAccepted: Bool = false
    @AppStorage("tcpHost") var tcpHost: String = "192.168.40.10"
    @AppStorage("tcpPort") var tcpPort: Int = 5000

    private var engine: MeshCoreEngine?
    private var transport: TcpInterface?
    private var pollTimer: Timer?
    private let scope: Kotlinx_coroutines_coreCoroutineScope

    init() {
        // GlobalScope-equivalent provided by the Shared framework's
        // coroutine interop; Phase 2 should scope this to the store.
        scope = IosScopeKt.meshCoreMainScope()
        let crypto = IosCryptoProvider()
        engine = MeshCoreEngine(
            scope: scope,
            crypto: crypto,
            nowSeconds: { KotlinLong(value: Int64(Date().timeIntervalSince1970)) },
            appName: "MeshCoreMobile",
            log: { _ in }
        )
        startPolling()
    }

    deinit {
        pollTimer?.invalidate()
    }

    // MARK: - Connection (TCP first; BLE pending IosBleTransport)

    func connectTcp() {
        guard tcpEnabled, let engine else { return }
        let t = TcpInterface(
            host: tcpHost,
            port: Int32(tcpPort),
            scope: scope,
            socketFactory: { host, port in TcpSocket(host: host, port: port.int32Value) },
            logger: { _ in }
        )
        transport = t
        engine.attach(t: t)
        Task {
            do {
                try await t.connect()
            } catch {
                self.engineStateLabel = "Connect failed: \(error.localizedDescription)"
            }
        }
    }

    func disconnect() {
        guard let t = transport else { return }
        engine?.detach()
        Task { try? await t.disconnect() }
        transport = nil
    }

    // MARK: - Messaging

    func sendDirectMessage(peerHex: String, text: String) {
        guard let engine, let key = HexKt.hexToBytesOrNull(hex: peerHex) else { return }
        Task {
            // K/N doesn't export Kotlin default params — attempt and
            // timestamp have to be passed explicitly here too (the
            // channel send below has always done this).
            _ = try? await engine.sendDirectMessage(
                recipientPubKey: key, text: text,
                attempt: 0,
                timestampSeconds: Int64(Date().timeIntervalSince1970))
            self.append(thread: "dm|\(peerHex)", message: UiMessage(
                senderName: nil, text: text,
                timestamp: Int64(Date().timeIntervalSince1970), outgoing: true))
        }
    }

    func sendChannelMessage(index: Int32, text: String) {
        guard let engine else { return }
        Task {
            // K/N doesn't export Kotlin default params — pass the
            // timestamp explicitly (it must match any dedup key).
            _ = try? await engine.sendChannelMessage(
                channelIndex: index, text: text,
                timestampSeconds: Int64(Date().timeIntervalSince1970))
            self.append(thread: "ch|\(index)", message: UiMessage(
                senderName: selfName, text: text,
                timestamp: Int64(Date().timeIntervalSince1970), outgoing: true))
        }
    }

    private func append(thread key: String, message: UiMessage) {
        threads[key, default: []].append(message)
    }

    // MARK: - State polling (Phase-1 bridge; see class comment)

    private func startPolling() {
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    private func refresh() {
        guard let engine else { return }
        let state = engine.state.value as? EngineState ?? EngineState.detached
        isReady = state == EngineState.ready
        isPlaintextLink = (engine.plaintextLink.value as? KotlinBoolean)?.boolValue ?? false
        engineStateLabel = {
            switch state {
            case EngineState.ready:
                return "Connected" + (isPlaintextLink ? " ⚠ unencrypted link" : "")
            case EngineState.handshaking: return "Handshaking…"
            case EngineState.connecting: return "Connecting…"
            default: return "Not connected"
            }
        }()

        if let info = engine.selfInfo.value as? SelfInfo {
            selfName = info.name
            selfKeyHex = info.publicKeyHex
        }
        if let live = engine.contacts.value as? [String: Contact] {
            contacts = live.values.map {
                UiContact(
                    id: $0.publicKeyHex,
                    name: $0.name,
                    type: $0.type,
                    latitude: $0.latitude?.doubleValue,
                    longitude: $0.longitude?.doubleValue
                )
            }.sorted { $0.name.lowercased() < $1.name.lowercased() }
        }
        if let live = engine.channels.value as? [Channel] {
            channels = live.map { UiChannel(id: $0.index, name: $0.name) }
        }
    }
}
