// Repeater / room admin: sign-in is the gate, then a hub of tiles from
// the shared repeaterHubTiles (so which tiles a guest sees is the tested
// rule Android uses). Status and Console are ported; the rest of the hub
// says plainly that it is Android-only for now.
//
// Not stored: the password. iOS keeps sessions in memory for the app's
// lifetime only (IOS_DECISIONS.md) — a repeater's ACL entry never
// expires, so signing in again costs one radio round trip.

import SwiftUI
import Shared

struct RepeaterView: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode

    private var session: AdminSession { store.adminSession(node.id) }

    var body: some View {
        Group {
            if session.signedIn {
                RepeaterHub(node: node, session: session)
            } else {
                RepeaterSignIn(node: node, isRoom: node.type == Codes.shared.ADV_TYPE_ROOM)
            }
        }
        .navigationTitle(node.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A room: sign in, then read and post (Android's openRoom). The sign-in
/// is not optional — a room only accepts posts from, and sends new posts
/// to, people who have signed in (shared RoomModel). Management is inside
/// the room, for an admin.
struct RoomView: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode

    var body: some View {
        if store.adminSession(node.id).signedIn {
            ConversationView(threadKey: "dm|\(node.id)")
        } else {
            RepeaterSignIn(node: node, isRoom: true)
                .navigationTitle(node.displayName)
                .navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct RepeaterSignIn: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    var isRoom: Bool = false
    @State private var password = ""
    @State private var busy = false
    @State private var problem: String?

    var body: some View {
        Form {
            Section {
                SecureField("Password", text: $password)
                    .textContentType(.password)
                Button(busy ? "Signing in…" : "Sign in") {
                    busy = true
                    problem = nil
                    Task {
                        problem = await store.signIn(node.id, password: password)
                        busy = false
                        password = ""
                    }
                }
                .disabled(busy || !store.isReady)
            } footer: {
                Text((isRoom ? RoomModelKt.roomPasswordHint() + " " : "") +
                     "A blank password works on a node that already knows this radio. The password crosses the mesh in the clear if the link is TCP.")
            }
            if let problem {
                Section { Text(problem).foregroundStyle(.red) }
            }
        }
    }
}

private struct RepeaterHub: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    let session: AdminSession
    @State private var clockNote: String?

    private var tiles: [HubTile] {
        RepeaterHubModelKt.repeaterHubTiles(
            role: RepeaterReadout.shared.roleOf(advType: node.type),
            session: session
        )
    }

    var body: some View {
        List {
            Section {
                HStack {
                    Text(node.hopsLabel).foregroundStyle(.secondary)
                    Spacer()
                    Text(session.chipLabel)
                        .font(.caption.bold())
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(session.isAdmin ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.2),
                                    in: Capsule())
                }
            }
            Section {
                ForEach(tiles, id: \.route) { tile in
                    switch tile.route {
                    case "status":
                        NavigationLink { RepeaterStatusView(node: node) } label: { tileLabel(tile) }
                    case "console":
                        NavigationLink { RepeaterConsoleView(node: node) } label: { tileLabel(tile) }
                    case "settings":
                        NavigationLink { RemoteSettingsView(node: node) } label: { tileLabel(tile) }
                    case "regions":
                        NavigationLink { RemoteRegionsView(node: node) } label: { tileLabel(tile) }
                    case "help":
                        NavigationLink { CommandHelpView(node: node, session: session) } label: { tileLabel(tile) }
                    default:
                        tileLabel(tile, note: "Not on iOS")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if RepeaterHubModelKt.showsGuestQueries(role: RepeaterReadout.shared.roleOf(advType: node.type), session: session) {
                GuestCard(node: node)
            }
            Section {
                if session.isAdmin {
                    Button("Sync clock from phone") {
                        Task { clockNote = await store.syncNodeClock(node.id) ? "Clock sync sent" : "Not sent — is the radio connected?" }
                    }
                    if let clockNote { Text(clockNote).font(.caption).foregroundStyle(.secondary) }
                }
                Button("Sign out", role: .destructive) { store.signOut(node.id) }
            } footer: {
                Text("Signing out is local: the node keeps this radio in its access list.")
            }
        }
    }

    private func tileLabel(_ tile: HubTile, note: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(tile.title)
            Text(note ?? tile.subtitle).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct RepeaterStatusView: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    @State private var groups: [RepeaterReadout.Group] = []
    @State private var busy = false
    @State private var silent = false

    var body: some View {
        List {
            if busy && groups.isEmpty {
                HStack { ProgressView(); Text("Asking the node…") }
            }
            if silent {
                Text("No answer. The node may be out of range; try again in a moment.")
                    .foregroundStyle(.secondary)
            }
            ForEach(groups, id: \.title) { group in
                Section(group.title) {
                    ForEach(group.rows, id: \.label) { row in
                        LabeledContent(row.label, value: row.value)
                    }
                }
            }
            TelemetrySection(node: node)
            NoiseWatchSection(node: node)
            if store.adminSession(node.id).isAdmin {
                AccessListSection(node: node)
            }
            if node.type == Codes.shared.ADV_TYPE_REPEATER {
                NeighboursSection(node: node, isAdmin: store.adminSession(node.id).isAdmin)
            }
        }
        .navigationTitle("Status")
        // Pull to refresh, or the button — Android's "Request status".
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { Task { await fetch() } } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel("Request status")
                    .disabled(busy || !store.isReady)
            }
        }
        .refreshable { await fetch() }
        .task { if groups.isEmpty { await fetch() } }
    }

    private func fetch() async {
        busy = true
        silent = false
        let result = await store.repeaterStatus(node.id)
        if let result { groups = result } else { silent = true }
        busy = false
    }
}

/// The console keeps its lines in the store, per node, for the app's
/// lifetime — so leaving and coming back doesn't lose them (Android keeps
/// them in its database; iOS keeps admin state in memory only).
struct RepeaterConsoleView: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    /// A command from Command help, put in the field to edit and send.
    var prefill: String? = nil
    @State private var command = ""
    @State private var busy = false

    private var lines: [String] { store.consoleLines[node.id] ?? [] }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                            Text(line)
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                                .id(i)
                        }
                    }
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: lines.count) { proxy.scrollTo(lines.count - 1, anchor: .bottom) }
            }
            HStack {
                TextField("get radio", text: $command)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(run)
                Button(action: run) { Image(systemName: "return") }
                    .disabled(busy || command.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(8)
        }
        .navigationTitle("Console")
        .onAppear { if let prefill, command.isEmpty { command = prefill } }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear") { store.consoleLines[node.id] = nil }.disabled(lines.isEmpty)
            }
        }
    }

    private func run() {
        let cmd = command.trimmingCharacters(in: .whitespaces)
        guard !cmd.isEmpty else { return }
        command = ""
        busy = true
        let key = node.id
        store.consoleLines[key, default: []].append(RepeaterAdmin.companion.displayed(line: "$ \(cmd)"))
        Task {
            let reply = await store.repeaterCli(key, command: cmd)
            store.consoleLines[key, default: []].append(RepeaterAdmin.companion.displayed(line: reply ?? "(no reply)"))
            busy = false
        }
    }
}
