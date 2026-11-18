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

    init(_ n: ContactNode, now: Int64) {
        id = n.keyHex
        name = n.name
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
        ).map { UiNode($0, now: now) }
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
