// Nodes: the radio's contacts by type, arranged by the shared
// NodeListModel (search, sort, filters) exactly as Android arranges
// them, each row answering "can I talk to this node?" with the shared
// Reachability verdict.

import SwiftUI
import Shared

/// Swift-side snapshot of one shared ContactNode, for SwiftUI identity.
struct UiNode: Identifiable, Hashable {
    let id: String
    let name: String
    let type: Int32
    let hopsLabel: String
    let heardLabel: String?
    let reachShort: String?
    let reachLine: String
    let isTwoWay: Bool
    let isFavorite: Bool
    let unread: Int
    let latitude: Double?
    let longitude: Double?
    let hasStoredPath: Bool
    /// From the radio's position, in the chosen units; nil if either can't be placed.
    let distance: String?

    init(_ n: ContactNode, now: Int64, nickname: String? = nil, distance: String? = nil) {
        self.distance = distance
        id = n.keyHex
        name = nickname ?? n.name
        type = n.type
        hopsLabel = n.hopsLabel
        heardLabel = n.heardLabel(nowSeconds: now)
        reachShort = n.reachLabel
        reachLine = n.reachLine
        isTwoWay = n.isTwoWay
        isFavorite = n.isFavorite
        unread = Int(n.unread)
        latitude = n.latitude?.doubleValue
        longitude = n.longitude?.doubleValue
        hasStoredPath = n.contact.storedPath.size > 0
    }

    var displayName: String { name.isEmpty ? String(id.prefix(12)) : name }
}

struct NodesView: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var tab: NodeTab = .contacts
    @State private var query = ""
    @State private var sort: NodeListModel.Sort = .activity
    @State private var filters: Set<NodeListModel.Filter> = []
    @State private var detail: UiNode?
    @State private var showMyContact = false
    @State private var discovery: DiscoveryRef?
    @State private var scanning = false
    @State private var removingStale = false
    @State private var showRepeats = false

    private static let tabs: [NodeTab] = [.contacts, .repeaters, .rooms, .sensors]

    /// Every sort, Nearest included: distance is from the radio's own
    /// advertised position, as on Android.
    private static var sorts: [NodeListModel.Sort] {
        let all = NodeListModel.Sort.values()
        return (0..<all.size).compactMap { all.get(index: $0) }
    }

    private static var allFilters: [NodeListModel.Filter] {
        let all = NodeListModel.Filter.values()
        return (0..<all.size).compactMap { all.get(index: $0) }
    }

    private var rows: [UiNode] {
        let now = MeshCoreStore.now
        return ContactNodes.shared.arrange(
            nodes: store.nodes, tab: tab, query: query, sort: sort, filters: filters, nowSeconds: now,
            origin: store.radioPosition
        ).map { UiNode($0, now: now, nickname: store.nickname($0.keyHex), distance: store.distance(to: $0)) }
    }

    private var counts: [NodeTab: Int] {
        ContactNodes.shared.counts(nodes: store.nodes).mapValues { Int(truncating: $0) }
    }

    var body: some View {
        NavigationStack {
            List {
                Picker("Type", selection: $tab) {
                    ForEach(Self.tabs, id: \.self) { t in
                        Text(counts[t].map { "\(t.title) \($0)" } ?? t.title).tag(t)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)

                if let problem = store.nodeActionProblem {
                    Text(problem).font(.caption).foregroundStyle(.red)
                } else if let note = store.nodeActionNote {
                    Text(note).font(.caption).foregroundStyle(.secondary)
                }
                if !store.heardNodes.isEmpty && query.isEmpty {
                    HeardSection()
                }
                ForEach(rows) { node in
                    row(node)
                }
            }
            .searchable(text: $query, prompt: "Name or key")
            .overlay {
                if store.nodes.isEmpty {
                    ContentUnavailableView(
                        "No contacts yet",
                        systemImage: "dot.radiowaves.left.and.right",
                        description: Text("Contacts appear when nearby nodes advertise.")
                    )
                } else if rows.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .navigationTitle("Nodes")
            .toolbar { toolbar }
            .navigationDestination(for: String.self) { key in
                ConversationView(threadKey: key)
            }
            .sheet(isPresented: $removingStale) { StaleNodesSheet() }
            .navigationDestination(isPresented: $showRepeats) { HeardRepeatsView() }
            .sheet(isPresented: $showMyContact) { MyContactSheet() }
            .sheet(item: $discovery) { d in DiscoverySheet(result: d) }
            .sheet(isPresented: $scanning) {
                QrScannerSheet { text in store.openScanned(text) }
            }
            .sheet(item: $detail) { node in
                NodeDetailSheet(node: node)
                    .presentationDetents([.medium, .large])
            }
        }
    }

    @ViewBuilder
    private func row(_ node: UiNode) -> some View {
        let content = NodeRow(node: node)
        if node.type == 1 {
            // A person: tapping opens the conversation; the detail is one
            // swipe or the info button away (Android: long-press).
            NavigationLink(value: "dm|\(node.id)") { content }
                .swipeActions(edge: .trailing) {
                    Button("Info") { detail = node }.tint(.blue)
                }
                .contextMenu { Button("Details") { detail = node } }
        } else if node.type == 3 {
            // A room opens its conversation, through the sign-in (Android's
            // openRoom); its management is inside, for an admin.
            NavigationLink { RoomView(node: node) } label: { content }
                .swipeActions(edge: .trailing) {
                    Button("Info") { detail = node }.tint(.blue)
                }
                .contextMenu { Button("Details") { detail = node } }
        } else if node.type == Codes.shared.ADV_TYPE_REPEATER || node.type == Codes.shared.ADV_TYPE_SENSOR {
            // A repeater or sensor opens its admin (sign-in is the gate) —
            // sensors run the same CLI (PARITY §7), as on Android; the
            // detail is a swipe or long-press away.
            NavigationLink { RepeaterView(node: node) } label: { content }
                .swipeActions(edge: .trailing) {
                    Button("Info") { detail = node }.tint(.blue)
                }
                .contextMenu { Button("Details") { detail = node } }
        } else {
            Button { detail = node } label: { content }
                .buttonStyle(.plain)
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Picker("Sort", selection: $sort) {
                    ForEach(Self.sorts, id: \.self) { s in Text(s.label).tag(s) }
                }
                Section("Show") {
                    ForEach(Self.allFilters, id: \.self) { f in
                        Toggle(f.label, isOn: Binding(
                            get: { filters.contains(f) },
                            set: { on in if on { filters.insert(f) } else { filters.remove(f) } }
                        ))
                    }
                }
            } label: {
                Image(systemName: filters.isEmpty
                      ? "line.3.horizontal.decrease.circle"
                      : "line.3.horizontal.decrease.circle.fill")
            }
        }
        ToolbarItem(placement: .topBarLeading) {
            Menu {
                Button { scanning = true } label: { Label("Scan a code", systemImage: "qrcode.viewfinder") }
                Button { store.nodeActionNote = store.pasteCode() } label: { Label("Paste a code", systemImage: "doc.on.clipboard") }
                Button { showMyContact = true } label: { Label("My contact", systemImage: "qrcode") }
                Button {
                    Task {
                        store.nodeActionNote = "Asking nearby repeaters…"
                        let d = await store.discoverNodes()
                        store.nodeActionNote = nil
                        discovery = DiscoveryRef(message: d?.message ?? "No repeater answered.",
                                                 keys: d?.answered.map { $0.publicKeyHex } ?? [])
                    }
                } label: {
                    Label("Discover nodes", systemImage: "dot.radiowaves.left.and.right")
                }
                .disabled(!store.isReady)
                Button { showRepeats = true } label: {
                    Label("Who repeats me", systemImage: "antenna.radiowaves.left.and.right")
                }
                Button(role: .destructive) { removingStale = true } label: {
                    Label("Remove stale nodes…", systemImage: "trash")
                }
                .disabled(!store.isReady)
                Section("Advertise this radio") {
                    Button("To neighbours (zero hop)") { store.sendAdvert(flood: false) }
                        .disabled(!store.isReady)
                    Button("Mesh-wide (flood)") { store.sendAdvert(flood: true) }
                        .disabled(!store.isReady)
                }
            } label: {
                Image(systemName: "plus.circle")
            }
        }
    }
}

private struct NodeRow: View {
    let node: UiNode

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    if node.isFavorite {
                        Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow)
                    }
                    Text(node.displayName).font(.body).lineLimit(1)
                }
                Text([node.hopsLabel, node.distance, node.heardLabel].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let reach = node.reachShort {
                    Text(reach)
                        .font(.caption2)
                        .foregroundStyle(node.isTwoWay ? .green : .secondary)
                }
            }
            Spacer()
            if node.unread > 0 {
                Text(node.unread > 99 ? "99+" : "\(node.unread)")
                    .font(.caption2.bold())
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(.tint, in: Capsule())
                    .foregroundStyle(.white)
            }
        }
    }
}

struct NodeDetailSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let node: UiNode
    @State private var confirmRemove = false
    @State private var checking = false
    @State private var reach: String?
    @State private var pinging = false
    @State private var pingLine: String?
    @State private var nick = ""
    @State private var radioName = ""
    @State private var renameNote: String?
    @State private var favourite = false
    @State private var sharing = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Route", value: node.hopsLabel)
                    if let d = node.distance { LabeledContent("Distance", value: d) }
                    if let heard = node.heardLabel { LabeledContent("Last heard", value: heard) }
                    if !node.reachLine.isEmpty {
                        Text(node.reachLine).font(.callout)
                    }
                }
                Section("Public key") {
                    Text(node.id)
                        .font(.system(.caption2, design: .monospaced))
                        .textSelection(.enabled)
                    Button("Copy key") { UIPasteboard.general.string = node.id }
                }
                if let lat = node.latitude, let lon = node.longitude, abs(lat) > 1e-6 || abs(lon) > 1e-6 {
                    Section("Location") {
                        Text(String(format: "%.5f, %.5f", lat, lon))
                    }
                }
                Section {
                    if BlockList.shared.isBlockableNodeType(advType: node.type) {
                        Toggle("Block direct messages", isOn: Binding(
                            get: { store.isBlocked(node.id) },
                            set: { store.setBlocked(node.id, $0) }
                        ))
                    } else {
                        Text(BlockList.shared.NOT_BLOCKABLE_NOTE).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section {
                    Toggle("Favourite", isOn: Binding(get: { favourite }, set: { on in
                        favourite = on
                        Task { if !(await store.setFavourite(node.id, on)) { favourite = !on } }
                    }))
                    .disabled(!store.isReady)
                    HStack {
                        TextField("Nickname (this phone only)", text: $nick)
                            .autocorrectionDisabled()
                        Button("Save") { store.setNickname(node.id, nick) }
                            .buttonStyle(.borderless)
                    }
                } footer: {
                    Text("A favourite is never removed by the stale-node sweep. A nickname replaces the advertised name in this app only.")
                }
                pathDetail
                renameAndTelemetry
                if node.type == 1 {
                    Section {
                        teleToggle("Battery", Codes.shared.CONTACT_FLAG_TELE_BASE)
                        teleToggle("Location", Codes.shared.CONTACT_FLAG_TELE_LOC)
                        teleToggle("Environment", Codes.shared.CONTACT_FLAG_TELE_ENV)
                    } header: {
                        Text("May read my telemetry")
                    } footer: {
                        Text("Applies when your radio's telemetry access (Settings → Mesh policies) is set to per-contact Flags.")
                    }
                }
                Section {
                    Button("Share contact…") { sharing = true }
                } footer: {
                    Text("A QR code of this contact's name and key, for someone else to add them.")
                }
                Section {
                    Button(pinging ? "Pinging…" : "Ping") {
                        Task { pinging = true; pingLine = await store.ping(node.id); pinging = false }
                    }
                    .disabled(pinging || !store.isReady)
                    if let pingLine { Text(pingLine).font(.callout) }
                } footer: {
                    Text("A ping reaches only nodes in direct range of your radio.")
                }
                Section {
                    NavigationLink("Routing / paths…") { RoutingView(keyHex: node.id, title: node.displayName) }
                    Button(checking ? "Checking…" : "Check if they can hear you") {
                        Task {
                            checking = true
                            reach = await store.checkReach(node.id)
                            checking = false
                        }
                    }
                    .disabled(checking || !store.isReady)
                    if checking { HStack { ProgressView(); Text("Asking them for the route both ways…") } }
                    if let reach { Text(reach).font(.callout) }
                } header: {
                    Text("Reachability")
                }
                Section {
                    if node.hasStoredPath {
                        Button("Reset path") { store.resetPath(node.id) }
                            .disabled(!store.isReady)
                    }
                    Button("Remove from radio", role: .destructive) { confirmRemove = true }
                        .disabled(!store.isReady)
                } footer: {
                    if node.hasStoredPath {
                        Text("Resetting the path makes the next message flood, and the reply teaches the radio a fresh route.")
                    }
                }
            }
            .navigationTitle(node.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $sharing) { ContactQrSheet(title: node.displayName, link: store.contactShareLink(node.id)) }
            .onAppear {
                nick = store.nickname(node.id) ?? ""
                radioName = store.liveContactName(node.id) ?? node.name
                favourite = node.isFavorite
            }
            .confirmationDialog(
                "Remove \(node.displayName) from the radio?",
                isPresented: $confirmRemove,
                titleVisibility: .visible
            ) {
                Button("Remove", role: .destructive) {
                    store.removeContact(node.id)
                    dismiss()
                }
            } message: {
                Text("The radio forgets this contact. It comes back only if it advertises again and the radio adds it.")
            }
        }
    }
}

/// Nodes heard advertising that the radio doesn't hold — auto-add is off,
/// or it doesn't auto-add that type. Add writes the node's own signed
/// advert to the radio; Dismiss forgets it here (shared DiscoveredBook).
private struct HeardSection: View {
    @EnvironmentObject var store: MeshCoreStore
    @AppStorage("heardCollapsed") private var collapsed = false
    @State private var busy: String?
    /// A far node's Add, held for the warning (shared DiscoveredBook.addWarning).
    @State private var warning: (node: HeardNode, text: String)?
    @State private var detail: HeardNode?

    private func tryAdd(_ n: HeardNode) {
        if let w = DiscoveredBook.companion.addWarning(node: n, origin: store.radioPosition, system: store.unitSystem) {
            warning = (n, w)
        } else {
            add(n)
        }
    }

    private func add(_ n: HeardNode) {
        Task { busy = n.keyHex; store.nodeActionNote = await store.addHeard(n); busy = nil }
    }

    var body: some View {
        Section {
            if !collapsed {
                ForEach(store.heardNodes, id: \.keyHex) { n in row(n) }
                Button("Clear all", role: .destructive) { store.clearHeard() }
            }
        } header: {
            Button {
                collapsed.toggle()
            } label: {
                HStack {
                    Text("Heard, not in contacts (\(store.heardNodes.count))")
                    Spacer()
                    Image(systemName: collapsed ? "chevron.right" : "chevron.down")
                }
            }
            .buttonStyle(.plain)
        }
        .alert("Add \(warning?.node.name ?? "this node")?",
               isPresented: Binding(get: { warning != nil }, set: { if !$0 { warning = nil } })) {
            Button("Add anyway") { if let n = warning?.node { add(n) }; warning = nil }
            Button("Cancel", role: .cancel) { warning = nil }
        } message: {
            Text(warning?.text ?? "")
        }
        .sheet(item: Binding(get: { detail.map(HeardRef.init) }, set: { detail = $0?.node })) { ref in
            HeardDetailSheet(node: ref.node,
                             onAdd: { detail = nil; tryAdd(ref.node) },
                             onForget: { detail = nil; store.dismissHeard(ref.node) })
        }
    }

    private struct HeardRef: Identifiable {
        let node: HeardNode
        var id: String { node.keyHex }
    }

    private func row(_ n: HeardNode) -> some View {
        HStack {
            Image(systemName: NodeMapView.symbol(n.type)).foregroundStyle(NodeMapView.colour(n.type))
            Button { detail = n } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(n.name.isEmpty ? String(n.keyHex.prefix(12)) : n.name).foregroundStyle(.primary)
                    Text(store.heardDetail(n)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if busy == n.keyHex {
                ProgressView()
            } else {
                Button("Add") { tryAdd(n) }
                .disabled(!store.isReady)
                Button { store.dismissHeard(n) } label: { Image(systemName: "xmark") }
            }
        }
        .buttonStyle(.borderless)
    }
}

extension NodeDetailSheet {
    /// Rename on the radio (shared engine; keeps the replay guard), and
    /// anyone's telemetry as far as they allow it — people and sensors
    /// need no sign-in (shared TelemetryFetch).
    @ViewBuilder var renameAndTelemetry: some View {
        Section {
            HStack {
                TextField("Name on the radio", text: $radioName).autocorrectionDisabled()
                Button("Rename") {
                    Task { renameNote = await store.renameOnRadio(node.id, radioName) ? "Renamed on the radio" : "Rename failed" }
                }
                .buttonStyle(.borderless)
                .disabled(!store.isReady || radioName.trimmingCharacters(in: .whitespaces).isEmpty || radioName == node.name)
            }
            if let renameNote { Text(renameNote).font(.caption).foregroundStyle(.secondary) }
        } footer: {
            Text("Changes the name your radio stores for this node. Their next advert may set it back.")
        }
        if node.type == Codes.shared.ADV_TYPE_CHAT || node.type == Codes.shared.ADV_TYPE_SENSOR {
            TelemetrySection(node: node)
        }
    }

    /// What the radio holds for reaching this node (Android's contact sheet):
    /// the out-path hop by hop at the contact's own width, the hash size,
    /// and its clock — shown only when that disagrees with what we heard.
    @ViewBuilder var pathDetail: some View {
        if let c = store.liveContact(node.id) {
            let lines = ContactNodes.shared.outPathLines(contact: c, names: store.contactNames)
            let width = ContactNodes.shared.hashWidthLine(contact: c, meshWidth: KotlinInt(int: store.meshWidth))
            let item = store.nodes.first { $0.keyHex == node.id }
            if !lines.isEmpty || width != nil || (item.map { LastHeard.shared.claimDisagrees(node: $0) } ?? false) {
                Section("Path") {
                    ForEach(lines, id: \.self) { Text($0).font(.system(.caption, design: .monospaced)) }
                    if let width { LabeledContent("Path hash size", value: width) }
                    if let item, LastHeard.shared.claimDisagrees(node: item) {
                        LabeledContent("Its own clock says",
                                       value: Date(timeIntervalSince1970: Double(item.lastSeen)).formatted(date: .abbreviated, time: .shortened)
                                           + " — this node's clock is wrong")
                    }
                }
            }
        }
    }

    /// One of the radio's per-contact telemetry flags, as a switch.
    func teleToggle(_ title: String, _ flag: Int32) -> some View {
        Toggle(title, isOn: Binding(
            get: { store.telemetryAllowed(node.id, flag: flag) },
            set: { on in Task { _ = await store.setTelemetryAllowed(node.id, flag: flag, on) } }
        ))
        .disabled(!store.isReady)
    }
}

/// A contact's card as a QR, with the link to copy or share.
private struct ContactQrSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let link: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let link, let image = QrCode.image(link) {
                    Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 280)
                    Text("Unsigned: whoever scans it sees the name you hold for this key, not one the node signed.")
                        .font(.callout).multilineTextAlignment(.center)
                    ShareLink(item: link) { Label("Send link", systemImage: "square.and.arrow.up") }
                    Button("Copy link") { UIPasteboard.general.string = link }
                } else {
                    Text("This contact is no longer on the radio.").foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

/// A heard-but-not-added node (Android's HeardDetailSheet): what is known,
/// and Add / Forget. Times are when this phone heard it, never the
/// advert's own clock.
private struct HeardDetailSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let node: HeardNode
    let onAdd: () -> Void
    let onForget: () -> Void

    private func stamp(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(DiscoveredBook.companion.kindLine(type: node.type)).foregroundStyle(.secondary)
                }
                Section("Public key") {
                    Text(node.keyHex).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    Button("Copy") { UIPasteboard.general.string = node.keyHex }
                }
                Section {
                    if let d = store.radioPosition.metresTo(lat: node.latitude, lon: node.longitude) {
                        LabeledContent("Distance", value: Units.shared.distance(metres: d.doubleValue, system: store.unitSystem))
                    }
                    LabeledContent("Last heard", value: stamp(node.lastHeardAt))
                    if node.firstHeardAt > 0 && node.firstHeardAt < node.lastHeardAt {
                        LabeledContent("First heard", value: stamp(node.firstHeardAt))
                    }
                    if let reach = HeardReach.shared.of(minHops: node.minHops, directSnr: node.directSnr) {
                        LabeledContent("Hops away", value: reach)
                    }
                }
                Section {
                    Button("Add to contacts", action: onAdd).disabled(!store.isReady)
                    Button("Forget this node", role: .destructive, action: onForget)
                } footer: {
                    Text(DiscoveredBook.companion.addHint(type: node.type))
                }
            }
            .navigationTitle(node.name.isEmpty ? "Unnamed node" : node.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

/// What "Discover nodes" heard back (Android's results dialog): the
/// sentence, and each held repeater that answered, opening its admin.
struct DiscoveryRef: Identifiable {
    let id = UUID()
    let message: String
    let keys: [String]
}

private struct DiscoverySheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let result: DiscoveryRef

    private var nodes: [UiNode] {
        let now = Int64(Date().timeIntervalSince1970)
        return result.keys.compactMap { key in
            store.nodes.first { $0.keyHex == key }.map { UiNode($0, now: now, nickname: store.nickname(key), distance: store.distance(to: $0)) }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section { Text(result.message) }
                if !nodes.isEmpty {
                    Section("Answered") {
                        ForEach(nodes) { n in
                            NavigationLink { RepeaterView(node: n) } label: {
                                VStack(alignment: .leading) {
                                    Text(n.displayName)
                                    Text([n.hopsLabel, n.distance].compactMap { $0 }.joined(separator: " · "))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Discover nodes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
