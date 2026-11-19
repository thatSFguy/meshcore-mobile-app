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
    /// Hops and last-hop signal (inbound) or the repeat badge (outgoing),
    /// worded by the shared MessageDetail so "routed" is never "direct".
    let footer: String?
    /// The stored row, for the info sheet.
    let source: BookMessage

    init(_ m: BookMessage) {
        source = m
        footer = MessageDetail.shared.bubbleFooter(m: m)
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
    /// A sentence for the last node action that worked (e.g. an advert
    /// went out) — the first iPhone test found success was silent.
    @Published var nodeActionNote: String?
    /// Every thread with traffic, most recent first.
    @Published var threads: [UiThread] = []
    @Published var unreadTotal: Int = 0
    /// Bumped on every book change; views re-read `messages(in:)`.
    @Published private(set) var bookRevision: Int = 0
    /// Set when the saved history could not be read and was set aside.
    @Published var historyProblem: String?

    /// TCP transport gate — same off-by-default + stern warning as
    /// Android (SCOPE.md).
    /// Bluetooth as a transport (Android's per-transport switch): off, the
    /// app neither reconnects to nor scans for a BLE radio — for someone
    /// who only uses a networked radio over TCP.
    @AppStorage("bleEnabled") var bleEnabled: Bool = true {
        willSet { objectWillChange.send() }
        didSet {
            if !bleEnabled && lastLinkKind == ConnectionMemoryCompanion.shared.KIND_BLE && supervisor != nil { disconnect() }
        }
    }
    @AppStorage("tcpEnabled") var tcpEnabled: Bool = false
    @AppStorage("tcpWarningAccepted") var tcpWarningAccepted: Bool = false
    @AppStorage("tcpHost") var tcpHost: String = "192.168.40.10"
    @AppStorage("tcpPort") var tcpPort: Int = 5000

    let engine: MeshCoreEngine
    private let crypto: IosCryptoProvider
    /// The radio's own settings, for the Settings → This node spokes.
    let node: NodeSettingsStore
    private lazy var channelJoin: ChannelJoin = {
        let join = ChannelJoin.companion.over(engine: engine, crypto: crypto)
        // A shared link's region is applied to the slot (shared rule).
        join.scopes = ChannelScopeBridge(node: node)
        return join
    }()
    private lazy var linkImport = LinkImport.companion.over(engine: engine, channels: channelJoin)
    /// Whichever transport is attached — TCP or BLE.
    private var transport: Transport?
    private let scope: Kotlinx_coroutines_coreCoroutineScope
    private var watches: [FlowWatch] = []

    /// Messaging for the radio identity currently connected. History is
    /// kept per identity (Android keys every row by self key the same
    /// way), so swapping radios never mixes two people's threads.
    private var session: MessagingSession?
    /// Nodes heard advertising that the radio doesn't hold (per radio).
    private(set) var heard: DiscoveredBook?
    /// "Message privately" handshakes for the open radio.
    private(set) var dmRequests: DmRequests?
    /// Routes used to each contact and how they did (per radio, persisted).
    private(set) var paths: PathHistoryBook?
    /// The open radio's message history (for routing's "reply the way
    /// they reached me").
    var sessionBook: MessageBook? { session?.book }
    private var bookWatch: FlowWatch?
    private var bookOwner: String?
    private var saveWork: Task<Void, Never>?

    let diag = DiagnosticsLog.shared

    private static func nowSeconds() -> Int64 { Int64(Date().timeIntervalSince1970) }
    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    init() {
        // Locals first: a class may not read its own properties until every
        // stored property is set.
        let scope = IosScopeKt.meshCoreMainScope()
        let crypto = IosCryptoProvider()
        self.scope = scope
        self.crypto = crypto
        let engine = MeshCoreEngine(
            scope: scope,
            crypto: crypto,
            nowSeconds: { KotlinLong(value: MeshCoreStore.nowSeconds()) },
            // Kotlin/Native exports no default parameters, so this must
            // match the engine's own default — the name the radio is told
            // in APP_START (CLAUDE.md: identify as MeshCoreHardened).
            appName: "MeshCoreHardened",
            log: DiagnosticsLog.shared.sink("Engine")
        )
        self.engine = engine
        node = NodeSettingsStore(engine: engine, scope: scope)
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
            self.diag.log("Store", "engine state \(state.name)")
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
        // The hop-hash width is a property of the mesh: when the radio
        // reports it, drop remembered routes that aren't whole hops at it.
        watches.append(FlowWatchKt.watch(flow: engine.deviceInfo, scope: scope) { [weak self] value in
            guard let self, let info = value as? DeviceInfo, let paths = self.paths else { return }
            let dropped = paths.repair(meshWidth: info.pathHashByteWidth)
            if dropped > 0 { self.diag.log("Store", "path history: dropped \(dropped) route(s) not whole hops at width \(info.pathHashByteWidth)") }
        })
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
            self.paths?.rememberContacts(contacts: Array(live.values))
            self.heard?.forgetHeld(heldKeys: Array(live.keys))
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
        s.log = DiagnosticsLog.shared.sink("Msg")
        // Path history, per radio, beside the message history.
        let pathsURL = url?.deletingLastPathComponent().appendingPathComponent("paths-\(selfKey.prefix(16)).json")
        let pathsJson = pathsURL.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let paths = PathHistoryBook.companion.fromJson(json: pathsJson, nowMs: { KotlinLong(value: MeshCoreStore.nowMs()) })
        s.paths = paths
        self.paths = paths
        // The discovery inbox, per radio, beside the history.
        let heardURL = url?.deletingLastPathComponent().appendingPathComponent("heard-\(selfKey.prefix(16)).json")
        let heardJson = heardURL.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let heard = DiscoveredBook.companion.fromJson(json: heardJson, nowMs: { KotlinLong(value: MeshCoreStore.nowMs()) })
        s.discovered = heard
        self.heard = heard
        paths.rememberContacts(contacts: Array(((engine.contacts.value as? [String: Contact]) ?? [:]).values))
        s.floodFallback = floodFallback
        s.channelRegions = node.channelRegions
        // "Message privately": its state is two small strings, per radio.
        let dm = DmRequests.companion.over(engine: engine, session: s)
        let dmKey = "dmRequests-\(selfKey.prefix(16))"
        dm.outstandingRaw = UserDefaults.standard.string(forKey: dmKey + "-out") ?? ""
        dm.handledRaw = UserDefaults.standard.string(forKey: dmKey + "-handled") ?? ""
        dm.onStateChanged = { [weak dm] in
            guard let dm else { return }
            UserDefaults.standard.set(dm.outstandingRaw, forKey: dmKey + "-out")
            UserDefaults.standard.set(dm.handledRaw, forKey: dmKey + "-handled")
        }
        s.dmRequests = dm
        dmRequests = dm
        node.onChannelRegionsChanged = { [weak self] in
            guard let self else { return }
            self.session?.channelRegions = self.node.channelRegions
        }
        diag.log("Store", "history opened for radio \(selfKey.prefix(12)): \((book.messages.value as? [Any])?.count ?? 0) messages")
        s.onNewMessage = { [weak self] key, sender, text in
            self?.notify(threadKey: key, sender: sender, text: text)
            // Each arrival can make a relaying repeater qualify (throttled).
            self?.runRepeaterAutoAdd(throttle: true)
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
        didSet {
            let removed = applyRetention()
            retentionNote = removed > 0 ? "Removed \(removed) older message(s)." : "Nothing older to remove."
        }
    }
    /// What changing the policy just removed (Android reports it too).
    @Published var retentionNote: String?

    var retentionPolicy: Retention.Policy { Retention.shared.decode(raw: retentionEncoded) }

    /// Trim history to the policy. Run when a book opens, when the policy
    /// changes, and when the app comes back to the foreground.
    @discardableResult
    func applyRetention() -> Int {
        guard let book = session?.book else { return 0 }
        let removed = Int(book.applyRetention(policy: retentionPolicy, nowMs: MeshCoreStore.nowMs()))
        if removed > 0 {
            refreshThreads()
            scheduleSave()
        }
        return removed
    }

    /// "Arrived via" (inbound) or "Repeated by" (outgoing) for the info sheet.
    func route(for m: BookMessage) -> MessageDetail.Route {
        let contacts = Array(((engine.contacts.value as? [String: Contact]) ?? [:]).values)
        if m.outgoing { return MessageDetail.shared.repeats(m: m, contacts: contacts) }
        let me = node.selfInfo
        let plausible = me.map { GeoKt.isPlausiblePosition(lat: KotlinDouble(double: $0.latitude), lon: KotlinDouble(double: $0.longitude)) } ?? false
        return MessageDetail.shared.arrival(
            m: m, contacts: contacts,
            selfLatitude: plausible ? me.map { KotlinDouble(double: $0.latitude) } : nil,
            selfLongitude: plausible ? me.map { KotlinDouble(double: $0.longitude) } : nil
        )
    }

    // MARK: - Message privately (shared DmRequests)

    /// The line a request/accept post shows instead of its wire text.
    func dmLine(_ m: UiMessage) -> String? {
        dmRequests?.line(text: m.text, senderName: m.senderName, outgoing: m.outgoing)
    }

    /// The requester's key when this post asks us and is unanswered.
    func dmAsker(_ m: UiMessage) -> String? {
        dmRequests?.pendingAsker(text: m.text, senderName: m.senderName, outgoing: m.outgoing)
    }

    func canAskPrivately(_ m: UiMessage) -> Bool {
        guard !m.outgoing, let dm = dmRequests else { return false }
        let people = contacts.filter { $0.type == 1 }.map(\.name)
        return dm.canAsk(senderName: m.senderName, contactNames: people)
    }

    func askPrivately(channel index: Int32, name: String) async -> String {
        guard let dm = dmRequests else { return "Not connected to a radio." }
        return (try? await dm.ask(index: index, targetName: name, nowSec: MeshCoreStore.nowSeconds())) ?? "The radio didn't send the request."
    }

    func acceptPrivately(channel index: Int32, name: String, keyHex: String) async -> String {
        guard let dm = dmRequests else { return "Not connected to a radio." }
        let msg = (try? await dm.accept(index: index, requesterName: name, requesterKeyHex: keyHex)) ?? "Couldn't answer the request."
        objectWillChange.send()
        return msg
    }

    func declinePrivately(_ keyHex: String) {
        dmRequests?.decline(requesterKeyHex: keyHex)
        objectWillChange.send()
    }

    /// Send a failed message of ours again (shared MessagingSession.resend).
    func resend(_ id: Int64) {
        _ = session?.resend(id: id)
    }

    /// Half-typed messages per thread, kept while the app runs (Android
    /// keeps them for the ViewModel's life) so stepping out of a
    /// conversation doesn't throw away what was being written.
    var drafts: [String: String] = [:]

    /// Names that have posted in a thread, newest first (mention candidates).
    /// "Names seen": who has posted here, with counts (shared MessageBook).
    func senders(_ threadKey: String) -> [MessageBook.SenderSeen] {
        session?.book.senders(threadKey: threadKey) ?? []
    }

    /// Whether a thread is a room server's (posts from many, through the room).
    func isRoom(_ threadKey: String) -> Bool {
        guard threadKey.hasPrefix("dm|") else { return false }
        let key = String(threadKey.dropFirst(3))
        return contacts.first(where: { $0.id == key })?.type == Codes.shared.ADV_TYPE_ROOM
    }

    func senderNames(_ threadKey: String) -> [String] {
        session?.book.senderNames(threadKey: threadKey) ?? []
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

    /// Purge: every message in the open radio's history; nil when none is open.
    func clearAllHistory() -> Int32? {
        guard let book = session?.book else { return nil }
        let n = book.clearAll()
        refreshThreads()
        return n
    }

    /// Mark a thread unread by hand ("come back to this").
    func markUnread(_ key: String) {
        session?.book.markUnread(threadKey: key)
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

    func scheduleSave() {
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
            if let heard {
                try Data(heard.toJson().utf8).write(
                    to: url.deletingLastPathComponent().appendingPathComponent("heard-\(owner.prefix(16)).json"),
                    options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
                )
            }
            if let paths {
                try Data(paths.toJson().utf8).write(
                    to: url.deletingLastPathComponent().appendingPathComponent("paths-\(owner.prefix(16)).json"),
                    options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
                )
            }
        } catch {
            historyProblem = "Message history could not be saved: \(error.localizedDescription)"
        }
    }

    // MARK: - App-side repeater auto-add (shared RepeaterAutoAdd)

    @AppStorage("autoAddRelaying") var autoAddRelaying: Bool = false {
        willSet { objectWillChange.send() }
        didSet { runRepeaterAutoAdd(throttle: false) }
    }
    @AppStorage("autoAddDirect") var autoAddDirect: Bool = false {
        willSet { objectWillChange.send() }
        didSet { runRepeaterAutoAdd(throttle: false) }
    }
    private lazy var repeaterAutoAdd = RepeaterAutoAdd.companion.over(engine: engine, nowMs: { KotlinLong(value: MeshCoreStore.nowMs()) })

    /// Add the heard repeaters a switched-on rule picks; logged, never shown.
    func runRepeaterAutoAdd(throttle: Bool) {
        guard isReady, let book = session?.book, let heard else { return }
        let rules = RepeaterSignals.Rules(relaysMyTraffic: autoAddRelaying, heardDirect: autoAddDirect)
        let pass = repeaterAutoAdd
        Task {
            let lines = (try? await pass.run(book: book, heard: heard, rules: rules, throttle: throttle)) ?? []
            for line in lines { diag.log("Store", line) }
            if !lines.isEmpty { scheduleSave() }
        }
    }

    // MARK: - Units and theme (Android's Appearance section)

    /// "system" / "metric" / "imperial" (shared UnitSystem constants).
    @AppStorage("units") var unitsPreference: String = UnitSystem.companion.FOLLOW_SYSTEM {
        willSet { objectWillChange.send() }
    }
    var unitSystem: UnitSystem {
        UnitSystem.companion.resolve(preference: unitsPreference, deviceCountry: Locale.current.region?.identifier)
    }
    /// "system" / "light" / "dark".
    @AppStorage("theme") var theme: String = "system" {
        willSet { objectWillChange.send() }
    }
    var colorScheme: ColorScheme? { theme == "light" ? .light : theme == "dark" ? .dark : nil }

    /// Where distances are measured from: the radio's own advertised
    /// position (as Android), so no phone location is needed.
    var radioPosition: SelfPosition {
        let info = node.selfInfo
        return SelfPosition(latitude: info.map { KotlinDouble(double: $0.latitude) },
                            longitude: info.map { KotlinDouble(double: $0.longitude) })
    }

    func distance(to n: ContactNode) -> String? {
        ContactNodes.shared.distanceLabel(node: n, origin: radioPosition, system: unitSystem)
    }

    // MARK: - Simple mode (shared AppModes, as Android)

    @AppStorage("appMode") var appModeRaw: String = "Full" {
        willSet { objectWillChange.send() }
    }

    var appMode: AppMode { AppMode.companion.of(stored: appModeRaw) }
    var isSimple: Bool { AppModes.shared.isSimple(mode: appMode) }

    func setSimple(_ on: Bool) { appModeRaw = AppModes.shared.stored(simple: on) }

    /// Whether a thread belongs on screen in the current mode: Simple hides
    /// the Public channel and anything that isn't a person.
    func showsThread(_ threadKey: String) -> Bool {
        let parts = threadKey.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return true }
        if parts[0] == "ch" {
            let kind = Int32(parts[1]).flatMap { channel($0) }.map { ChannelKinds.shared.of(name: $0.name, psk: $0.psk, crypto: crypto) }
            return AppModes.shared.showsThread(mode: appMode, isChannel: true, channelKind: kind, contactType: nil)
        }
        let type = contacts.first(where: { $0.id == parts[1] })?.type
        return AppModes.shared.showsThread(mode: appMode, isChannel: false, channelKind: nil,
                                           contactType: type.map { KotlinInt(int: $0) })
    }

    // MARK: - Notifications

    @AppStorage("notificationsEnabled") var notificationsEnabled: Bool = true {
        willSet { objectWillChange.send() }
    }
    @AppStorage("notifyDirect") var notifyDirect: Bool = true {
        willSet { objectWillChange.send() }
    }
    @AppStorage("notifyChannels") var notifyChannels: Bool = true {
        willSet { objectWillChange.send() }
    }

    /// Flood on the last DM attempt (Settings → Mesh policies). MeshCore's
    /// documented default is on.
    @AppStorage("floodFallback") var floodFallback: Bool = true {
        willSet { objectWillChange.send() }
        didSet { session?.floodFallback = floodFallback }
    }

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// Threads muted one by one (shared MutedThreads); unread still counts.
    @AppStorage("mutedThreads") private var mutedThreadsRaw: String = ""

    /// Threads pinned to the top of Chats (shared ThreadKeys).
    @AppStorage("pinnedThreads") private var pinnedThreadsRaw: String = ""

    func isPinnedThread(_ threadKey: String) -> Bool { ThreadKeys.shared.contains(raw: pinnedThreadsRaw, threadKey: threadKey) }

    func togglePinThread(_ threadKey: String) {
        objectWillChange.send()
        pinnedThreadsRaw = ThreadKeys.shared.toggled(raw: pinnedThreadsRaw, threadKey: threadKey)
    }

    func isMuted(_ threadKey: String) -> Bool { MutedThreads.shared.isMuted(raw: mutedThreadsRaw, threadKey: threadKey) }

    func toggleMute(_ threadKey: String) {
        objectWillChange.send()
        mutedThreadsRaw = MutedThreads.shared.toggled(raw: mutedThreadsRaw, threadKey: threadKey)
    }

    private func notify(threadKey: String, sender: String?, text: String) {
        guard notificationsEnabled, !isMuted(threadKey) else { return }
        let isChannel = threadKey.hasPrefix("ch|")
        if isChannel ? !notifyChannels : !notifyDirect { return }
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
            // The name as the radio holds it, as Android shows it: a hashtag
            // channel's name already carries its '#', and prefixing another
            // read "# #test".
            return channels.first { $0.id == idx && !$0.name.isEmpty }.map { $0.name } ?? "Channel \(parts[1])"
        }
        let hex = parts[1]
        if let nick = nickname(hex) { return nick }
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
        diag.log("Store", "restore connection: \(memory.map { String(describing: type(of: $0)) } ?? "nothing remembered")")
        if bleEnabled, let ble = memory as? ConnectionMemoryBle,
           let id = UUID(uuidString: ble.address),
           let peripheral = scanner.knownPeripheral(id) {
            // The radio reconnected to from memory belongs in the saved list
            // too (it was connected before the list existed).
            remember(SavedNode(kind: ConnectionMemoryCompanion.shared.KIND_BLE, address: ble.address, port: nil, name: ble.name))
            superviseBle(peripheral, name: ble.name ?? "radio")
        } else if memory is ConnectionMemoryTcp {
            superviseTcp()
        }
    }

    /// Attach over BLE to a radio the scanner found.
    func connectBle(_ radio: DiscoveredRadio) {
        guard bleEnabled else { return }
        lastLinkKind = ConnectionMemoryCompanion.shared.KIND_BLE
        lastBleId = radio.id.uuidString
        lastBleName = radio.name
        remember(SavedNode(kind: ConnectionMemoryCompanion.shared.KIND_BLE, address: radio.id.uuidString, port: nil, name: radio.name))
        superviseBle(radio.peripheral, name: radio.name)
        requestNotificationPermission()
    }

    func connectTcp() {
        guard tcpEnabled else { return }
        lastLinkKind = ConnectionMemoryCompanion.shared.KIND_TCP
        remember(SavedNode(kind: ConnectionMemoryCompanion.shared.KIND_TCP, address: tcpHost,
                           port: KotlinInt(int: Int32(clamping: tcpPort)), name: nil))
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
        diag.log("Store", "BLE link to \(name)")
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
                logger: DiagnosticsLog.shared.sink("TCP")
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
                if let message {
                    self?.engineStateLabel = message
                    self?.diag.log("Link", "error: \(message)")
                }
            },
            log: DiagnosticsLog.shared.sink("Link"),
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

    // MARK: - Saved radios (shared SavedNodes, as Android)

    @AppStorage("savedNodes") private var savedNodesRaw: String = ""

    var savedRadios: [SavedNode] { SavedNodes.shared.decode(raw: savedNodesRaw) }

    private func remember(_ node: SavedNode) {
        objectWillChange.send()
        savedNodesRaw = SavedNodes.shared.encode(nodes: SavedNodes.shared.with(nodes: savedRadios, node: node))
    }

    /// Whether [node] is the radio the app reconnects to.
    func isCurrent(_ node: SavedNode) -> Bool {
        SavedNodes.shared.isBle(node: node) ? (lastLinkKind == ConnectionMemoryCompanion.shared.KIND_BLE && lastBleId == node.address)
            : (lastLinkKind == ConnectionMemoryCompanion.shared.KIND_TCP && tcpHost == node.address)
    }

    /// Connect to a saved radio; nil on success, else the sentence to show.
    func connectSaved(_ node: SavedNode) -> String? {
        if SavedNodes.shared.isBle(node: node) {
            guard bleEnabled else { return "Turn on Bluetooth under Transports first." }
            guard let id = UUID(uuidString: node.address), let p = scanner.knownPeripheral(id) else {
                return "iOS doesn't know this radio right now. Scan once, and it will reconnect from then on."
            }
            if supervisor != nil { disconnect() }
            lastLinkKind = ConnectionMemoryCompanion.shared.KIND_BLE
            lastBleId = node.address
            lastBleName = node.name ?? ""
            superviseBle(p, name: SavedNodes.shared.label(node: node))
        } else {
            guard tcpEnabled else { return "Turn on TCP under Transports first." }
            tcpHost = node.address
            if let port = node.port { tcpPort = Int(port.int32Value) }
            if supervisor != nil { disconnect() }
            connectTcp()
        }
        return nil
    }

    /// Forget a saved radio — and stop reconnecting to it, or it would come
    /// straight back (the bug Android's rule fixed).
    func forgetSaved(_ node: SavedNode) {
        let wasCurrent = isCurrent(node)
        objectWillChange.send()
        savedNodesRaw = SavedNodes.shared.encode(nodes: SavedNodes.shared.without(nodes: savedRadios, key: node.key))
        if wasCurrent {
            lastLinkKind = ""
            if supervisor != nil { disconnect() }
        }
    }

    /// Drop the link and stay down. Auto-reconnect resumes only on the
    /// next launch, and only if it is on.
    func disconnect() {
        diag.log("Store", "disconnect requested")
        stopSupervisor()
        linkTarget = nil
        objectWillChange.send()
    }

    /// Scanning ends any live link: the scanner has to take the BLE
    /// central's delegate back, which silences the transport using it.
    func scanForRadios() {
        diag.log("Store", "scan for radios")
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
            self.diag.log("Store", "reset path \(keyHex.prefix(12)): \(ok ? "ok" : "refused")")
            self.nodeActionProblem = ok ? nil : "The radio did not confirm the path reset."
        }
    }

    /// Remove a contact from the RADIO (not just this phone). The view
    /// confirms first; this is not undoable from the app.
    func removeContact(_ keyHex: String) {
        guard let key = HexKt.hexToBytesOrNull(hex: keyHex) else { return }
        Task {
            let ok = (try? await engine.removeContact(pubKey: key))?.boolValue ?? false
            self.diag.log("Store", "remove contact \(keyHex.prefix(12)): \(ok ? "ok" : "refused")")
            self.nodeActionProblem = ok ? nil : "The radio did not remove the contact."
        }
    }

    /// Announce this radio. Zero-hop reaches only direct neighbours;
    /// flood goes mesh-wide and costs everyone airtime.
    func sendAdvert(flood: Bool) {
        Task {
            let ok = (try? await engine.sendSelfAdvert(flood: flood))?.boolValue ?? false
            self.diag.log("Store", "advert (\(flood ? "flood" : "zero hop")): \(ok ? "sent" : "refused")")
            self.nodeActionProblem = ok ? nil : "The radio did not send the advert."
            self.nodeActionNote = ok ? (flood ? "Flood advert sent mesh-wide." : "Zero-hop advert sent to nodes in direct range.") : nil
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

    /// Showing our code is an invitation: an answer on a shared channel from
    /// whoever scanned it should add them (shared DmRequests.myCode, as Android).
    func myCodeShown() {
        _ = dmRequests?.myCode(nowSec: Int64(Date().timeIntervalSince1970))
    }

    /// A scanned code goes to the same confirmation as an opened link.
    /// Unlike a link it is not filtered to meshcore:// first — a stray
    /// code should be explained ("not a MeshCore link"), not ignored.
    /// A community code waiting for the person's confirmation.
    @Published var pendingCommunity: String?

    /// The community's name, for the confirmation; nil if the code can't be read.
    func communityName(_ text: String) -> String? { CommunityCode.shared.parse(text: text)?.name }

    /// Join a confirmed community code: its secret into the Keychain first,
    /// then its public channel (shared ChannelJoin.joinCommunity).
    func joinCommunity(_ text: String) {
        let join = channelJoin
        Task {
            let out = try? await join.joinCommunity(text: text) { idHex, secret in
                KotlinBoolean(bool: CommunityKeychain.keep(idHex: idHex, secret: secret.toData()))
            }
            channelMessage = out?.message ?? "Couldn't join the community."
            nodeActionNote = channelMessage // the scanner lives on Nodes
            diag.log("Store", "community join: \(channelMessage ?? "")")
        }
    }

    func openScanned(_ text: String) {
        if CommunityCode.shared.isCommunity(text: text) {
            pendingCommunity = text
            return
        }
        pendingLink = text
    }

    func openIncoming(_ url: URL) {
        if let link = linkInbox.openNow(raw: url.absoluteString, ready: isReady) { pendingLink = link }
    }

    /// What a link is, before anything is written (shared LinkImport).
    func previewLink(_ link: String) -> LinkImport.Preview {
        linkImport.preview(link: link)
    }

    /// Apply a preview the person confirmed; the outcome lands in
    /// `channelMessage` (the one sentence slot the sheets show).
    func applyLink(_ preview: LinkImport.Preview) {
        let li = linkImport
        Task {
            do {
                channelMessage = try await li.apply(preview: preview)
            } catch {
                channelMessage = error.localizedDescription
            }
        }
    }

    /// Apply a confirmed settings code; `region` nil leaves the region alone.
    func applyRadioLink(_ preview: LinkImport.Preview, region: String?) {
        let li = linkImport
        Task {
            do {
                channelMessage = try await li.applyRadio(preview: preview, pickedRegion: region)
            } catch {
                channelMessage = error.localizedDescription
            }
            diag.log("Store", "settings code applied: \(channelMessage ?? "?")")
        }
    }

    /// The last channel action's outcome, as a sentence to show.
    @Published var channelMessage: String?

    // MARK: - Backup
    //
    // The file format, allow-list and restore steps are shared
    // (ConfigBackup, BackupSettings, BackupRestore), under Android's
    // preference names, so either app restores the other's file.

    /// This app's settings under the shared backup names.
    private var backupSettings: [String: String] {
        [
            "transport_ble_enabled": "true",
            BackupSettings.shared.TCP: String(tcpEnabled),
            "auto_reconnect": String(autoReconnect),
            "diagnostics_enabled": String(UserDefaults.standard.bool(forKey: DiagnosticsLog.enabledKey)),
            "notifications_enabled": String(notificationsEnabled),
        ]
    }

    /// The backup file's text, or a sentence saying why there is none.
    /// Slow with a passphrase (PBKDF2), so the work runs off the main thread.
    func buildBackup(passphrase: String?) async -> Result<String, BackupError> {
        guard isReady, !selfKeyHex.isEmpty else { return .failure(BackupError("Connect to a radio first: a backup is of a radio's contacts and channels.")) }
        let contacts = Array(((engine.contacts.value as? [String: Contact]) ?? [:]).values)
        let channels = (engine.channels.value as? [Channel]) ?? []
        let plain = BackupRestore.companion.plainFor(
            selfKeyHex: selfKeyHex,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
            nowSeconds: MeshCoreStore.nowSeconds(),
            settings: backupSettings,
            contacts: contacts,
            channels: channels,
            regions: node.regions
        )
        let crypto = self.crypto
        let result: Result<String, BackupError> = await Task.detached {
            do {
                return .success(try BackupRestore.companion.export(crypto: crypto, plain: plain, channels: channels, passphrase: passphrase))
            } catch {
                return .failure(BackupError("Couldn't build the backup: \(error.localizedDescription)"))
            }
        }.value
        diag.log("Store", "backup built: \((try? result.get()) != nil ? "ok" : "failed"), secrets: \(passphrase != nil)")
        return result
    }

    var backupFileName: String { BackupRestore.companion.suggestedFileName(nowSeconds: MeshCoreStore.nowSeconds()) }

    func readBackup(_ url: URL) -> ConfigBackup.Parsed? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        let data = (try? handle.read(upToCount: Int(BackupRestore.companion.MAX_FILE_BYTES) + 1)) ?? Data()
        guard data.count <= Int(BackupRestore.companion.MAX_FILE_BYTES),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return ConfigBackup.shared.decode(text: text)
    }

    func restoreBackup(_ parsed: ConfigBackup.Parsed, options: BackupRestore.Options, passphrase: String?) async -> String {
        do {
            let report = try await BackupRestore.companion.over(engine: engine).restore(
                crypto: crypto,
                parsed: parsed,
                options: options,
                passphrase: (passphrase?.isEmpty ?? true) ? nil : passphrase,
                tcpWarningAccepted: tcpWarningAccepted,
                connected: isReady
            )
            for (key, value) in report.settings {
                let on = value == "true"
                switch key {
                case BackupSettings.shared.TCP: tcpEnabled = on
                case "auto_reconnect": autoReconnect = on
                case "diagnostics_enabled": UserDefaults.standard.set(on, forKey: DiagnosticsLog.enabledKey)
                case "notifications_enabled": notificationsEnabled = on
                default: break // Android-only settings (theme, units, map tiles…)
                }
            }
            node.mergeRegions(report.regions)
            diag.log("Store", "backup restored: \(report.message)")
            return report.message
        } catch {
            return "Restore failed: \(error.localizedDescription)"
        }
    }

    func joinHashtag(_ input: String) { runChannel { try await $0.joinHashtag(input: input) } }
    func createPrivateChannel(_ name: String) { runChannel { try await $0.createPrivate(nameInput: name) } }
    func joinPrivateChannel(name: String, key: String) {
        runChannel { try await $0.joinPrivate(nameInput: name, keyInput: key) }
    }
    func joinSharedLink(_ link: String) { runChannel { try await $0.joinShared(link: link) } }
    func leaveChannel(_ index: Int32) { runChannel { try await $0.leave(index: index) } }
    func joinPublic() { runChannel { try await $0.joinPublic() } }
    var hasPublic: Bool { _ = channels; return channelJoin.hasPublic() }

    /// "Paste a code": a contact, channel, community or settings code from
    /// the clipboard goes to the same confirmation as a scan; nil on
    /// success, else why not (shared ScannedCode.pasteProblem).
    func pasteCode() -> String? {
        let raw = UIPasteboard.general.string
        if let problem = ScannedCode.companion.pasteProblem(raw: raw) { return problem }
        if let code = ScannedCode.companion.extract(text: raw ?? "") { openScanned(code) }
        return nil
    }

    private func runChannel(_ op: @escaping (ChannelJoin) async throws -> ChannelJoin.Outcome) {
        let join = channelJoin
        Task {
            do {
                channelMessage = try await op(join).message
            } catch {
                channelMessage = error.localizedDescription
            }
        }
    }

    /// The key of a channel slot, for the editor (read from the radio's copy).
    func channelPskHex(_ index: Int32) -> String? {
        channel(index).map { $0.pskHex }
    }

    /// Rename a channel or change its key (shared ChannelJoin.edit).
    func editChannel(_ index: Int32, name: String, pskHex: String) {
        let join = channelJoin
        Task {
            channelMessage = (try? await join.edit(index: index, name: name, pskHex: pskHex)) ?? "Channel write failed"
            diag.log("Store", "channel \(index) edit: \(channelMessage ?? "")")
        }
    }

    /// Whether the radio holds a group's private channel (setup step 4).
    var inAGroup: Bool {
        ((engine.channels.value as? [Channel]) ?? []).contains {
            !$0.name.isEmpty && SetupChecklist.shared.isGroupChannel(kind: ChannelKinds.shared.of(name: $0.name, psk: $0.psk, crypto: crypto))
        }
    }

    /// First-run setup has been finished or skipped (Android's setupComplete).
    @AppStorage("setupComplete") var setupComplete: Bool = false {
        willSet { objectWillChange.send() }
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

    lazy var repeaterAdmin = RepeaterAdmin(engine: engine)
    /// Repeaters' neighbour tables as read, for the map (memory only).
    let neighbourBook = NeighbourBook()
    @Published private var adminSessions: [String: AdminSession] = [:]
    /// Repeater console lines per node, already redacted (memory only).
    @Published var consoleLines: [String: [String]] = [:]

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
            // The outcome only — never the password.
            diag.log("Admin", "sign-in \(keyHex.prefix(12)): \(outcome)")
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

    func repeaterStatus(_ keyHex: String) async -> [RepeaterReadout.Group]? {
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

    func isFiltered(_ name: String) -> Bool {
        guard let n = BlockList.shared.canonicalName(raw: name) else { return false }
        return filteredNames.contains(n)
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
    /// Every reaction the wire format can carry (Android's ＋ list).
    var allReactions: [String] { Reactions.shared.ALL }

    func react(to id: Int64, with emoji: String) {
        _ = session?.react(id: id, emoji: emoji)
    }

    func sendChannelMessage(index: Int32, text: String) {
        // Unscoped: the session sends on the radio's own flood scope.
        // Per-channel regions are Android-only for now (IOS_DECISIONS.md).
        _ = session?.sendChannel(index: index, text: text)
    }
}

/// A backup that could not be built, as the sentence to show.
struct BackupError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}
