// The single ObservableObject bridging the Kotlin `Shared` framework to
// SwiftUI.
//
// Phase 2 (2026-11-17): state arrives by SUBSCRIPTION, not polling. Every
// StateFlow the UI shows is watched through the shared `watch(...)`
// bridge (FlowWatch.kt), which calls back on the main dispatcher. The
// old 1-second poll lagged, and could never see an inbound message at
// all — a message is a SharedFlow event, not a state.
//
// Messaging is the shared `MessagingSession` + `MessageBook`: the same
// retry ladder, dedup and reaction rules Android runs, tested on the JVM
// (shared/src/commonTest/.../messaging). This file only persists the
// book and mirrors it into @Published values.

import Foundation
import SwiftUI
import UserNotifications
import CoreBluetooth
import Shared

struct UiContact: Identifiable, Hashable {
    let id: String        // pubkey hex
    let name: String
    let type: Int32
    let latitude: Double?
    let longitude: Double?
    let hasStoredPath: Bool
    let hops: Int?        // nil = floods / unknown
    let lastAdvert: Int64 // advert timestamp, epoch seconds (sender's clock)
}

struct UiChannel: Identifiable, Hashable {
    let id: Int32         // slot index
    let name: String
}

enum UiStatus: Hashable {
    case pending, sent, delivered, failed

    init(_ s: MessageStatus) {
        switch s {
        case .delivered: self = .delivered
        case .sent: self = .sent
        case .failed: self = .failed
        default: self = .pending
        }
    }
}

struct UiReaction: Hashable {
    let emoji: String
    let count: Int
}

struct UiMessage: Identifiable, Hashable {
    let id: Int64
    let senderName: String?
    let text: String
    let timestamp: Int64
    let outgoing: Bool
    let status: UiStatus
    let attempts: Int
    let copies: Int
    let hops: Int?
    let snr: Double?
    let reactions: [UiReaction]

    init(_ m: BookMessage) {
        id = m.id
        senderName = m.senderName
        text = m.text
        timestamp = m.timestamp
        outgoing = m.outgoing
        status = UiStatus(m.status)
        attempts = Int(m.attempts)
        copies = Int(m.copies)
        hops = m.hops.map { Int(truncating: $0) }
        snr = m.snr.map { Double(truncating: $0) }
        reactions = m.reactions
            .map { UiReaction(emoji: $0.key, count: Int(truncating: $0.value)) }
            .sorted { ($0.count, $0.emoji) > ($1.count, $1.emoji) }
    }
}

struct UiThread: Identifiable, Hashable {
    let id: String        // "dm|<hex>" / "ch|<idx>"
    let lastText: String?
    let lastSender: String?
    let lastOutgoing: Bool
    let lastAtMs: Int64
    let unread: Int
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
    /// Contacts joined with message history (shared ContactNodes), for
    /// the Nodes list. Kotlin objects; the view arranges and maps them.
    @Published private(set) var nodes: [ContactNode] = []
    /// The connected radio as labelled rows (shared RadioFacts).
    @Published private(set) var radioFacts: [(label: String, value: String)] = []
    /// A sentence for the last node action that went wrong, or nil.
    @Published var nodeActionProblem: String?
    /// Every thread with traffic, most recent first.
    @Published var threads: [UiThread] = []
    @Published var unreadTotal: Int = 0
    /// Bumped on every book change; views re-read `messages(in:)`.
    @Published private(set) var bookRevision: Int = 0
    /// Set when the saved history could not be read and was set aside.
    @Published var historyProblem: String?

    /// TCP transport gate — same off-by-default + stern warning as
    /// Android (SCOPE.md).
    @AppStorage("tcpEnabled") var tcpEnabled: Bool = false
    @AppStorage("tcpWarningAccepted") var tcpWarningAccepted: Bool = false
    @AppStorage("tcpHost") var tcpHost: String = "192.168.40.10"
    @AppStorage("tcpPort") var tcpPort: Int = 5000

    private let engine: MeshCoreEngine
    private let crypto = IosCryptoProvider()
    private lazy var channelJoin = ChannelJoin.companion.over(engine: engine, crypto: crypto)
    private lazy var linkImport = LinkImport.companion.over(engine: engine, channels: channelJoin)
    /// Whichever transport is attached — TCP or BLE.
    private var transport: Transport?
    private let scope: Kotlinx_coroutines_coreCoroutineScope
    private var watches: [FlowWatch] = []

    /// Messaging for the radio identity currently connected. History is
    /// kept per identity (Android keys every row by self key the same
    /// way), so swapping radios never mixes two people's threads.
    private var session: MessagingSession?
    private var bookWatch: FlowWatch?
    private var bookOwner: String?
    private var saveWork: Task<Void, Never>?

    private static func nowSeconds() -> Int64 { Int64(Date().timeIntervalSince1970) }
    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    init() {
        scope = IosScopeKt.meshCoreMainScope()
        engine = MeshCoreEngine(
            scope: scope,
            crypto: crypto,
            nowSeconds: { KotlinLong(value: MeshCoreStore.nowSeconds()) },
            // Kotlin/Native exports no default parameters, so this must
            // match the engine's own default — the name the radio is told
            // in APP_START (CLAUDE.md: identify as MeshCoreHardened).
            appName: "MeshCoreHardened",
            log: { _ in }
        )
        startWatching()
        startConnectionMemory()
    }

    // MARK: - Subscriptions

    private func startWatching() {
        watches.append(FlowWatchKt.watch(flow: engine.state, scope: scope) { [weak self] value in
            guard let self, let state = value as? EngineState else { return }
            let wasReady = self.isReady
            self.isReady = state == EngineState.ready
            self.engineStateLabel = self.label(for: state)
            if self.isReady && !wasReady {
                Task { try? await self.engine.refreshBattery() }
                if let held = self.linkInbox.onReady() { self.pendingLink = held }
            }
        })
        for flow in [engine.selfInfo, engine.deviceInfo, engine.battery] as [Kotlinx_coroutines_coreFlow] {
            watches.append(FlowWatchKt.watch(flow: flow, scope: scope) { [weak self] _ in
                self?.rebuildRadioFacts()
            })
        }
        watches.append(FlowWatchKt.watch(flow: engine.plaintextLink, scope: scope) { [weak self] value in
            guard let self else { return }
            self.isPlaintextLink = (value as? KotlinBoolean)?.boolValue ?? false
            self.engineStateLabel = self.label(for: self.engine.state.value as? EngineState ?? .detached)
        })
        watches.append(FlowWatchKt.watch(flow: engine.selfInfo, scope: scope) { [weak self] value in
            guard let self, let info = value as? SelfInfo else { return }
            self.selfName = info.name
            self.selfKeyHex = info.publicKeyHex
            self.openBook(for: info.publicKeyHex)
        })
        watches.append(FlowWatchKt.watch(flow: engine.contacts, scope: scope) { [weak self] value in
            guard let self, let live = value as? [String: Contact] else { return }
            self.contacts = live.values.map { c in
                let info = c.pathInfo
                return UiContact(
                    id: c.publicKeyHex,
                    name: c.name,
                    type: c.type,
                    latitude: c.latitude?.doubleValue,
                    longitude: c.longitude?.doubleValue,
                    hasStoredPath: c.storedPath.size > 0,
                    hops: info.isFlood ? nil : Int(info.hops),
                    lastAdvert: c.timestamp
                )
            }.sorted { $0.name.lowercased() < $1.name.lowercased() }
            self.rebuildNodes()
        })
        watches.append(FlowWatchKt.watch(flow: engine.channels, scope: scope) { [weak self] value in
            guard let self, let live = value as? [Channel] else { return }
            self.channels = live.map { UiChannel(id: $0.index, name: $0.name) }
        })
    }

    private func label(for state: EngineState) -> String {
        switch state {
        case EngineState.ready:
            return "Connected" + (isPlaintextLink ? " ⚠ unencrypted link" : "")
        case EngineState.handshaking: return "Handshaking…"
        case EngineState.connecting: return "Connecting…"
        default: return "Not connected"
        }
    }

    // MARK: - Message history

    private func historyURL(for selfKey: String) throws -> URL {
        let dir = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).appendingPathComponent("messages", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            // Message history never leaves the phone — no iCloud backup.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var d = dir
            try? d.setResourceValues(values)
        }
        // The identity's first 8 bytes name the file: unique on any mesh
        // a person uses, and no need to put a whole key in a filename.
        return dir.appendingPathComponent("book-\(selfKey.prefix(16)).json")
    }

    private func openBook(for selfKey: String) {
        guard !selfKey.isEmpty, selfKey != bookOwner else { return }
        saveNow()
        session?.stop()
        bookWatch?.cancel()

        var json: String?
        var url: URL?
        do {
            url = try historyURL(for: selfKey)
            if let u = url, FileManager.default.fileExists(atPath: u.path) {
                json = try String(contentsOf: u, encoding: .utf8)
            }
        } catch {
            historyProblem = "Message history could not be opened: \(error.localizedDescription)"
        }
        let book = MessageBook.companion.fromJson(
            json: json,
            nowMs: { KotlinLong(value: MeshCoreStore.nowMs()) },
            keepPerThread: MessageBook.companion.DEFAULT_KEEP_PER_THREAD
        )
        // fromJson never throws — an unreadable file yields an empty book.
        // Keep the bad file aside rather than overwriting it on the next
        // save, so history that could still be recovered is not destroyed.
        if !MessageBook.companion.isReadable(json: json), let u = url {
            let aside = u.appendingPathExtension("unreadable-\(MeshCoreStore.nowSeconds())")
            try? FileManager.default.moveItem(at: u, to: aside)
            historyProblem = "Saved message history could not be read. It was kept as \(aside.lastPathComponent)."
        }

        let s = MessagingSession(
            radio: EngineMessagingRadio(engine: engine, clock: { KotlinLong(value: MeshCoreStore.nowSeconds()) }),
            book: book,
            scope: scope
        )
        s.onChanged = { [weak self] in self?.scheduleSave() }
        s.onNewMessage = { [weak self] key, sender, text in
            self?.notify(threadKey: key, sender: sender, text: text)
        }
        session = s
        bookOwner = selfKey
        pushBlocks()
        bookWatch = FlowWatchKt.watch(flow: book.messages, scope: scope) { [weak self] _ in
            self?.refreshThreads()
        }
        s.start()
        refreshThreads()
        applyRetention()
    }

    private func refreshThreads() {
        guard let book = session?.book else { return }
        threads = book.summaries().map { s in
            UiThread(
                id: s.threadKey,
                lastText: s.last?.text,
                lastSender: s.last?.senderName,
                lastOutgoing: s.last?.outgoing ?? false,
                lastAtMs: s.last?.receivedAtMs ?? 0,
                unread: Int(s.unread)
            )
        }
        unreadTotal = Int(book.unreadTotal())
        bookRevision &+= 1
        rebuildNodes()
    }

    private func rebuildRadioFacts() {
        radioFacts = RadioFacts.shared.of(
            selfInfo: engine.selfInfo.value as? SelfInfo,
            device: engine.deviceInfo.value as? DeviceInfo,
            battery: engine.battery.value as? BatteryAndStorage
        ).map { (label: $0.label, value: $0.value) }
    }

    func refreshBattery() {
        Task { try? await engine.refreshBattery() }
    }

    private func rebuildNodes() {
        let live = (engine.contacts.value as? [String: Contact]).map { Array($0.values) } ?? []
        nodes = ContactNodes.shared.build(
            contacts: live,
            book: session?.book,
            nowSeconds: MeshCoreStore.nowSeconds()
        )
    }

    static var now: Int64 { nowSeconds() }

    /// History retention (shared Retention policy, stored encoded). An
    /// unreadable stored value decodes to "keep everything" — failing
    /// safe means keeping history, never deleting it.
    @AppStorage("retentionPolicy") var retentionEncoded: String = "forever" {
        // @AppStorage does not publish from inside an ObservableObject.
        willSet { objectWillChange.send() }
        didSet { applyRetention() }
    }

    var retentionPolicy: RetentionPolicy { Retention.shared.decode(raw: retentionEncoded) }

    /// Trim history to the policy. Run when a book opens, when the policy
    /// changes, and when the app comes back to the foreground.
    func applyRetention() {
        guard let book = session?.book else { return }
        let removed = book.applyRetention(policy: retentionPolicy, nowMs: MeshCoreStore.nowMs())
        if removed > 0 {
            refreshThreads()
            scheduleSave()
        }
    }

    func messages(in threadKey: String) -> [UiMessage] {
        session?.book.thread(threadKey: threadKey).map(UiMessage.init) ?? []
    }

    /// The thread on screen: it accrues no unread and raises no banner.
    func setActiveThread(_ key: String?) {
        session?.book.activeThread = key
        refreshThreads()
        scheduleSave()
    }

    func deleteThread(_ key: String) {
        session?.book.deleteThread(threadKey: key)
        refreshThreads()
        scheduleSave()
    }

    func deleteMessage(_ id: Int64) {
        session?.book.delete(id: id)
        refreshThreads()
        scheduleSave()
    }

    private func scheduleSave() {
        saveWork?.cancel()
        saveWork = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    /// Write the book now. Called on a debounce and when the app leaves
    /// the foreground. Encrypted at rest by iOS Data Protection; see
    /// IOS_DECISIONS.md for why this is a file and not a database.
    func saveNow() {
        saveWork?.cancel()
        saveWork = nil
        guard let owner = bookOwner, let book = session?.book,
              let url = try? historyURL(for: owner) else { return }
        do {
            try Data(book.toJson().utf8).write(
                to: url,
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
        } catch {
            historyProblem = "Message history could not be saved: \(error.localizedDescription)"
        }
    }

    // MARK: - Notifications

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func notify(threadKey: String, sender: String?, text: String) {
        let content = UNMutableNotificationContent()
        content.title = title(for: threadKey)
        content.body = sender.map { "\($0): \(text)" } ?? text
        content.threadIdentifier = threadKey
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// The name a thread goes by, for headers and banners.
    func title(for threadKey: String) -> String {
        let parts = threadKey.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return threadKey }
        if parts[0] == "ch" {
            let idx = Int32(parts[1]) ?? -1
            return channels.first { $0.id == idx }.map { "# \($0.name)" } ?? "Channel \(parts[1])"
        }
        let hex = parts[1]
        if let c = contacts.first(where: { $0.id == hex }), !c.name.isEmpty { return c.name }
        if let c = contacts.first(where: { $0.id.hasPrefix(hex) }), !c.name.isEmpty { return c.name }
        return String(hex.prefix(12))
    }

    // MARK: - Connection
    //
    // Every connection runs under the shared ReconnectSupervisor — the
    // build → connect → run → backoff loop Android's service uses, tested
    // in shared against fake transports. A dropped link comes back on its
    // own; Disconnect is the only thing that stops it. The last radio is
    // remembered (shared ConnectionMemory) and restored at launch.

    /// The app's one CBCentralManager owner. Lives as long as the store,
    /// so the central a transport took over is never torn down under it.
    let scanner = BleScanner()
    private var supervisor: ReconnectSupervisor?

    @AppStorage("autoReconnect") var autoReconnect: Bool = true
    @AppStorage("lastLinkKind") private var lastLinkKind: String = ""
    @AppStorage("lastBleId") private var lastBleId: String = ""
    @AppStorage("lastBleName") private var lastBleName: String = ""
    /// What the current (or last attempted) link is to, for Settings.
    @Published private(set) var linkTarget: String?

    var isLinked: Bool { supervisor != nil }

    private func startConnectionMemory() {
        scanner.onPoweredOn = { [weak self] in self?.restoreConnection() }
        if scanner.isPoweredOn { restoreConnection() }
    }

    /// Reconnect to the remembered radio, if there is one and the user
    /// has not turned that off. Never second-guesses a live link.
    private func restoreConnection() {
        guard supervisor == nil else { return }
        let memory = ConnectionMemoryCompanion.shared.resolve(
            autoReconnect: autoReconnect,
            kind: lastLinkKind,
            bleAddress: lastBleId,
            bleName: lastBleName,
            tcpHost: tcpHost,
            tcpPort: KotlinInt(int: Int32(clamping: tcpPort)),
            tcpEnabled: tcpEnabled
        )
        if let ble = memory as? ConnectionMemoryBle,
           let id = UUID(uuidString: ble.address),
           let peripheral = scanner.knownPeripheral(id) {
            superviseBle(peripheral, name: ble.name ?? "radio")
        } else if memory is ConnectionMemoryTcp {
            superviseTcp()
        }
    }

    /// Attach over BLE to a radio the scanner found.
    func connectBle(_ radio: DiscoveredRadio) {
        lastLinkKind = ConnectionMemoryCompanion.shared.KIND_BLE
        lastBleId = radio.id.uuidString
        lastBleName = radio.name
        superviseBle(radio.peripheral, name: radio.name)
        requestNotificationPermission()
    }

    func connectTcp() {
        guard tcpEnabled else { return }
        lastLinkKind = ConnectionMemoryCompanion.shared.KIND_TCP
        superviseTcp()
    }

    private func superviseBle(_ peripheral: CBPeripheral, name: String) {
        // `IosBleTransport` does not scan — it takes a discovered
        // peripheral, as Android's transport takes a MAC — and makes
        // itself the central's delegate, so scanning stops first. A fresh
        // transport per attempt: each one re-takes the delegate. On iOS a
        // pending connect never times out, so an out-of-range radio just
        // waits in connect() until it is back.
        let central = scanner.takeCentralForConnect()
        linkTarget = name
        supervise { _ in IosBleTransport(central: central, peripheral: peripheral) }
    }

    private func superviseTcp() {
        let host = tcpHost
        let port = Int32(clamping: tcpPort)
        let scope = self.scope
        linkTarget = "\(host):\(port) (TCP)"
        supervise { _ in
            TcpInterface(
                host: host,
                port: port,
                scope: scope,
                socketFactory: { h, p in TcpSocket(host: h, port: p.int32Value) },
                logger: { _ in }
            )
        }
    }

    private func supervise(_ build: @escaping (ReconnectAttempt) -> Transport) {
        stopSupervisor()
        let sup = ReconnectSupervisor(
            scope: scope,
            build: build,
            onTransport: { [weak self] raw, forEngine in
                // The engine gets the write-watched wrapper, so a write
                // that fails on a link claiming to be up ends the link.
                self?.transport = raw
                self?.engine.attach(t: forEngine)
            },
            onError: { [weak self] message in
                // Every failure the transports raise is already a sentence
                // meant for a person, so surface it rather than replace it.
                if let message { self?.engineStateLabel = message }
            },
            log: { _ in },
            now: { KotlinLong(value: MeshCoreStore.nowMs()) }
        )
        supervisor = sup
        sup.start()
        objectWillChange.send()
    }

    private func stopSupervisor() {
        supervisor?.stop()
        supervisor = nil
        engine.detach()
        if let t = transport { Task { try? await t.disconnect() } }
        transport = nil
    }

    /// Drop the link and stay down. Auto-reconnect resumes only on the
    /// next launch, and only if it is on.
    func disconnect() {
        stopSupervisor()
        linkTarget = nil
        objectWillChange.send()
    }

    /// Scanning ends any live link: the scanner has to take the BLE
    /// central's delegate back, which silences the transport using it.
    func scanForRadios() {
        if supervisor != nil { disconnect() }
        scanner.startScan()
    }

    // MARK: - Node actions

    /// Forget the radio's stored route so the next message floods and
    /// the reply teaches it a fresh one.
    func resetPath(_ keyHex: String) {
        guard let key = HexKt.hexToBytesOrNull(hex: keyHex) else { return }
        Task {
            let ok = (try? await engine.resetPath(pubKey: key))?.boolValue ?? false
            self.nodeActionProblem = ok ? nil : "The radio did not confirm the path reset."
        }
    }

    /// Remove a contact from the RADIO (not just this phone). The view
    /// confirms first; this is not undoable from the app.
    func removeContact(_ keyHex: String) {
        guard let key = HexKt.hexToBytesOrNull(hex: keyHex) else { return }
        Task {
            let ok = (try? await engine.removeContact(pubKey: key))?.boolValue ?? false
            self.nodeActionProblem = ok ? nil : "The radio did not remove the contact."
        }
    }

    /// Announce this radio. Zero-hop reaches only direct neighbours;
    /// flood goes mesh-wide and costs everyone airtime.
    func sendAdvert(flood: Bool) {
        Task {
            let ok = (try? await engine.sendSelfAdvert(flood: flood))?.boolValue ?? false
            self.nodeActionProblem = ok ? nil : "The radio did not send the advert."
        }
    }

    // MARK: - Channels
    //
    // The rules (validation, the key is the channel, full vs unread) are
    // the shared ChannelJoin; this only runs it and reports its sentence.

    /// A meshcore:// link another app opened, waiting for the person to
    /// confirm it. Held until the radio is ready (shared LinkInbox), since
    /// nothing can be joined before the radio's slots are read.
    @Published var pendingLink: String?
    private let linkInbox = LinkInbox()

    /// This radio as a contact link, for the "My contact" QR. Nil until
    /// the radio has said who it is.
    var myContactLink: String? {
        guard !selfKeyHex.isEmpty else { return nil }
        return ShareUri.shared.encodeContact(name: selfName, pubKeyHex: selfKeyHex, type: 1)
    }

    /// A scanned code goes to the same confirmation as an opened link.
    /// Unlike a link it is not filtered to meshcore:// first — a stray
    /// code should be explained ("not a MeshCore link"), not ignored.
    func openScanned(_ text: String) {
        pendingLink = text
    }

    func openIncoming(_ url: URL) {
        let offer = linkInbox.offer(raw: url.absoluteString, ready: isReady)
        if let open = offer as? LinkInboxOfferOpen { pendingLink = open.link }
    }

    /// What a link is, before anything is written (shared LinkImport).
    func previewLink(_ link: String) -> LinkImportPreview {
        linkImport.preview(link: link)
    }

    /// Apply a preview the person confirmed; the outcome lands in
    /// `channelMessage` (the one sentence slot the sheets show).
    func applyLink(_ preview: LinkImportPreview) {
        let li = linkImport
        Task {
            do {
                channelMessage = try await li.apply(preview: preview)
            } catch {
                channelMessage = error.localizedDescription
            }
        }
    }

    /// The last channel action's outcome, as a sentence to show.
    @Published var channelMessage: String?

    func joinHashtag(_ input: String) { runChannel { try await $0.joinHashtag(input: input) } }
    func createPrivateChannel(_ name: String) { runChannel { try await $0.createPrivate(nameInput: name) } }
    func joinPrivateChannel(name: String, key: String) {
        runChannel { try await $0.joinPrivate(nameInput: name, keyInput: key) }
    }
    func joinSharedLink(_ link: String) { runChannel { try await $0.joinShared(link: link) } }
    func leaveChannel(_ index: Int32) { runChannel { try await $0.leave(index: index) } }

    private func runChannel(_ op: @escaping (ChannelJoin) async throws -> ChannelJoinOutcome) {
        let join = channelJoin
        Task {
            do {
                channelMessage = try await op(join).message
            } catch {
                channelMessage = error.localizedDescription
            }
        }
    }

    private func channel(_ index: Int32) -> Channel? {
        (engine.channels.value as? [Channel])?.first { $0.index == index }
    }

    /// The link that hands someone this channel's key.
    func shareLink(_ index: Int32) -> String? {
        channel(index).map { channelJoin.shareLink(channel: $0) }
    }

    /// "Hashtag · anyone can read it" / "Private · not secure" — the
    /// same subtitle Android shows, so neither platform oversells it.
    func channelSubtitle(_ index: Int32) -> String {
        guard let ch = channel(index) else { return "Obfuscated, not secure" }
        let kind = ChannelKinds.shared.of(name: ch.name, psk: ch.psk, crypto: crypto)
        return ChannelSetupKt.channelSubtitle(kind: kind, region: nil)
    }

    func isPrivateChannel(_ index: Int32) -> Bool {
        guard let ch = channel(index) else { return false }
        // By name: `Private` is a Swift keyword, and how Kotlin/Native
        // escapes an enum entry spelled like one is not worth depending on.
        return ChannelKinds.shared.of(name: ch.name, psk: ch.psk, crypto: crypto).name == "Private"
    }

    // MARK: - Messaging

    func sendDirectMessage(peerHex: String, text: String) {
        _ = session?.sendDirect(keyHex: peerHex, text: text)
    }

    // MARK: - Repeater admin
    //
    // Sessions are in memory only, keyed by node; no password is stored.

    private lazy var repeaterAdmin = RepeaterAdmin(engine: engine)
    @Published private var adminSessions: [String: AdminSession] = [:]

    func adminSession(_ keyHex: String) -> AdminSession {
        adminSessions[keyHex] ?? RepeaterReadout.shared.signedOut
    }

    /// Sign in; nil on success, otherwise the sentence to show.
    func signIn(_ keyHex: String, password: String) async -> String? {
        guard let type = contacts.first(where: { $0.id == keyHex })?.type else {
            return "That node is no longer on the radio."
        }
        do {
            let outcome = try await repeaterAdmin.signIn(keyHex: keyHex, password: password)
            let granted = RepeaterReadout.shared.session(advType: type, outcome: outcome)
            if granted.signedIn { adminSessions[keyHex] = granted }
            return RepeaterReadout.shared.failure(outcome: outcome)
        } catch {
            return error.localizedDescription
        }
    }

    func signOut(_ keyHex: String) {
        adminSessions[keyHex] = nil
    }

    func repeaterStatus(_ keyHex: String) async -> [RepeaterReadoutGroup]? {
        guard let s = try? await repeaterAdmin.status(keyHex: keyHex) else { return nil }
        return RepeaterReadout.shared.status(s: s)
    }

    func repeaterCli(_ keyHex: String, command: String) async -> String? {
        (try? await repeaterAdmin.cli(keyHex: keyHex, command: command)) ?? nil
    }

    // MARK: - Blocking
    //
    // A DM block is on the public key (real); a channel "block" is a
    // filter on an unauthenticated name (noise only). Rules: shared
    // BlockList, enforced in MessagingSession before a row exists.

    @AppStorage("blockedKeys") private var blockedKeysRaw: String = ""
    @AppStorage("filteredNames") private var filteredNamesRaw: String = ""

    var blockedKeys: [String] { blockedKeysRaw.split(separator: "\n").map(String.init) }
    var filteredNames: [String] { filteredNamesRaw.split(separator: "\n").map(String.init) }

    func isBlocked(_ keyHex: String) -> Bool {
        guard let k = BlockList.shared.canonicalKey(raw: keyHex) else { return false }
        return blockedKeys.contains(k)
    }

    func setBlocked(_ keyHex: String, _ blocked: Bool) {
        guard let k = BlockList.shared.canonicalKey(raw: keyHex) else { return }
        var set = Set(blockedKeys)
        if blocked { set.insert(k) } else { set.remove(k) }
        guard set.count <= Int(BlockList.shared.MAX_ENTRIES) else { return }
        objectWillChange.send()
        blockedKeysRaw = set.sorted().joined(separator: "\n")
        pushBlocks()
    }

    func setFiltered(_ name: String, _ filtered: Bool) {
        guard let n = BlockList.shared.canonicalName(raw: name) else { return }
        var set = Set(filteredNames)
        if filtered { set.insert(n) } else { set.remove(n) }
        guard set.count <= Int(BlockList.shared.MAX_ENTRIES) else { return }
        objectWillChange.send()
        filteredNamesRaw = set.sorted().joined(separator: "\n")
        pushBlocks()
    }

    private func pushBlocks() {
        session?.blockedKeys = Set(blockedKeys)
        session?.filteredNames = Set(filteredNames)
    }

    /// The quick-reaction row, in the shared wire order.
    var quickReactions: [String] { Reactions.shared.QUICK }

    func react(to id: Int64, with emoji: String) {
        _ = session?.react(id: id, emoji: emoji)
    }

    func sendChannelMessage(index: Int32, text: String) {
        // Unscoped: the session sends on the radio's own flood scope.
        // Per-channel regions are Android-only for now (IOS_DECISIONS.md).
        _ = session?.sendChannel(index: index, text: text)
    }
}
