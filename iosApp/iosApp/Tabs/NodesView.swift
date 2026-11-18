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

    init(_ n: ContactNode, now: Int64, nickname: String? = nil) {
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
    @State private var scanning = false
    @State private var removingStale = false
    @State private var showRepeats = false

    private static let tabs: [NodeTab] = [.contacts, .repeaters, .rooms, .sensors]

    /// Every sort except Nearest, which needs this phone's position — not
    /// read on iOS yet.
    private static var sorts: [NodeListModel.Sort] {
        let all = NodeListModel.Sort.values()
        return (0..<all.size).compactMap { all.get(index: $0) }.filter { $0 != .distance }
    }

    private static var allFilters: [NodeListModel.Filter] {
        let all = NodeListModel.Filter.values()
        return (0..<all.size).compactMap { all.get(index: $0) }
    }

    private var rows: [UiNode] {
        let now = MeshCoreStore.now
        return ContactNodes.shared.arrange(
            nodes: store.nodes, tab: tab, query: query, sort: sort, filters: filters, nowSeconds: now
        ).map { UiNode($0, now: now, nickname: store.nickname($0.keyHex)) }
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
        } else if node.type == 2 || node.type == 3 {
            // A repeater or room opens its admin (sign-in is the gate),
            // as on Android; the detail is a swipe or long-press away.
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
                Button { showMyContact = true } label: { Label("My contact", systemImage: "qrcode") }
                Button {
                    Task { store.nodeActionNote = "Asking nearby repeaters…"; store.nodeActionNote = await store.discoverNodes() }
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
                Text([node.hopsLabel, node.heardLabel].compactMap { $0 }.joined(separator: " · "))
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
    @State private var favourite = false
    @State private var sharing = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Route", value: node.hopsLabel)
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
    }

    private func row(_ n: HeardNode) -> some View {
        HStack {
            Image(systemName: NodeMapView.symbol(n.type)).foregroundStyle(NodeMapView.colour(n.type))
            VStack(alignment: .leading, spacing: 2) {
                Text(n.name.isEmpty ? String(n.keyHex.prefix(12)) : n.name)
                Text(store.heardDetail(n)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if busy == n.keyHex {
                ProgressView()
            } else {
                Button("Add") {
                    Task { busy = n.keyHex; store.nodeActionNote = await store.addHeard(n); busy = nil }
                }
                .disabled(!store.isReady)
                Button { store.dismissHeard(n) } label: { Image(systemName: "xmark") }
            }
        }
        .buttonStyle(.borderless)
    }
}

extension NodeDetailSheet {
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
