// A contact's routing (iOS Phase 4) — Android's RoutingSheet: Auto /
// Flood / Manual, pinning hops by tapping relays (stored as full keys,
// so each hop's hash is derived at the mesh's CURRENT width), the route
// a reply would take, path history with its delivery record, and trace.
// Wording and rules are the shared `Routing`; there are no confirmation
// dialogs, as on Android — every action says what happened.

import SwiftUI
import Shared

struct RoutingView: View {
    @EnvironmentObject var store: MeshCoreStore
    let keyHex: String
    let title: String

    @State private var mode: RoutingMode = Routing.companion.AUTO
    @State private var hops: [HopSelection.Hop] = []
    @State private var showTokens = false
    @State private var tokens = ""
    @State private var message: String?
    @State private var busy = false
    @State private var traced: Routing.TraceView?
    @State private var tracing = false

    private var width: Int32 { store.meshWidth }

    var body: some View {
        Form {
            Section {
                Picker("Routing", selection: Binding(get: { mode }, set: { choose($0) })) {
                    ForEach(Routing.companion.MODES, id: \.self) { m in
                        Text(Routing.companion.modeLabel(mode: m)).tag(m)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(busy || !store.isReady)
            } footer: {
                Text(Routing.companion.modeHint(mode: mode))
            }

            if mode == Routing.companion.MANUAL { manualSections }
            if let sketch = store.storedRouteSketch(keyHex) {
                PathSketchMap(title: "Route on map", sketch: sketch)
            }
            replySection
            historySection
            traceSection

            if let message {
                Section { Text(message).font(.callout) }
            }
        }
        .navigationTitle("Routing")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            mode = store.routingMode(keyHex)
            hops = store.storedHops(keyHex)
        }
    }

    // MARK: Manual

    @ViewBuilder private var manualSections: some View {
        Section {
            if hops.isEmpty {
                Text(Routing.companion.NO_HOPS).foregroundStyle(.secondary)
            }
            ForEach(hops.indices, id: \.self) { i in
                HStack {
                    Text("\(i + 1).").foregroundStyle(.secondary)
                    Text(hops[i].label(hashWidth: width))
                        .font(.system(.callout, design: .monospaced))
                        .foregroundStyle(unresolved.contains(i) ? .red : .primary)
                    Spacer()
                    Button { hops = HopSelection.shared.move(hops: hops, index: Int32(i), delta: -1) } label: { Image(systemName: "chevron.up") }
                        .disabled(i == 0)
                    Button { hops = HopSelection.shared.move(hops: hops, index: Int32(i), delta: 1) } label: { Image(systemName: "chevron.down") }
                        .disabled(i == hops.count - 1)
                    Button { hops = HopSelection.shared.removeAt(hops: hops, index: Int32(i)) } label: { Image(systemName: "xmark") }
                }
                .buttonStyle(.borderless)
            }
            if !unresolved.isEmpty {
                Text(Routing.companion.unresolvedWarning(indices: unresolved.map { KotlinInt(int: Int32($0)) }, meshWidth: width))
                    .font(.caption).foregroundStyle(.red)
            }
            HStack {
                Button("Clear route") { hops = [] }
                Spacer()
                Button("Apply path") { applyManual() }
                    .disabled(hops.isEmpty || busy || !store.isReady)
            }
            .buttonStyle(.borderless)
        } header: {
            Text("Route")
        }

        Section {
            let relays = store.relays()
            if relays.isEmpty {
                Text(Routing.companion.NO_RELAYS).foregroundStyle(.secondary)
            }
            ForEach(relays, id: \.publicKeyHex) { r in
                Button {
                    hops = HopSelection.shared.add(
                        hops: hops,
                        hop: HopSelection.shared.fromContact(keyHex: r.publicKeyHex, name: r.name),
                        hashWidth: width
                    )
                } label: {
                    HStack {
                        Text(r.name.isEmpty ? String(r.publicKeyHex.prefix(12)) : r.name).foregroundStyle(.primary)
                        Spacer()
                        Text(r.publicKeyHex.prefix(Int(width) * 2))
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(hops.count >= Int(PathCodec.shared.maxHopsFor(hashWidth: width)))
            }
        } header: {
            Text("Relays")
        } footer: {
            Text(Routing.companion.relayHint(hopCount: Int32(hops.count), meshWidth: width))
        }

        Section {
            Toggle("Enter hops as hex", isOn: $showTokens.onChange {
                if showTokens { tokens = HopSelection.shared.toTokens(hops: hops, hashWidth: width) }
            })
            if showTokens {
                TextField("Hops", text: $tokens)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Use these hops") { useTokens() }
            }
        } footer: {
            if showTokens { Text(Routing.companion.tokensHint(meshWidth: width)) }
        }
    }

    private var unresolved: [Int] {
        HopSelection.shared.unresolvedIndices(hops: hops, hashWidth: width).map { Int(truncating: $0) }
    }

    // MARK: Reply, history, trace

    @ViewBuilder private var replySection: some View {
        if let reply = store.replyRoute(keyHex), let bytes = HexKt.hexToBytesOrNull(hex: reply) {
            let replyHops = HopSelection.shared.fromPath(path: bytes, hashWidth: width, contactsByKeyHex: store.contactNames)
            Section {
                Text(replyHops.map { $0.label(hashWidth: width) }.joined(separator: " → "))
                    .font(.system(.callout, design: .monospaced))
                Button("Use this route") {
                    hops = replyHops
                    mode = Routing.companion.MANUAL
                }
            } header: {
                Text("Reply the way they reached me")
            } footer: {
                Text("Their last message arrived over \(replyHops.count) repeater(s). Reversed here into the order a reply would travel.")
            }
        }
    }

    @ViewBuilder private var historySection: some View {
        Section {
            let rows = store.pathRows(keyHex)
            if rows.isEmpty {
                Text(Routing.companion.NO_PATHS).foregroundStyle(.secondary)
            }
            ForEach(rows, id: \.pathHex) { row in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(PathHistoryBook.companion.hopsLine(row: row, meshWidth: width))
                            .font(.system(.callout, design: .monospaced))
                        Text(PathHistoryBook.companion.recordLine(row: row, meshWidth: width, workedLabel: worked(row)))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if !row.isFlood {
                        Button("Use") { use(row) }.disabled(busy || !store.isReady)
                    }
                    Button { store.deletePath(keyHex, row.pathHex) } label: { Image(systemName: "xmark") }
                }
                .buttonStyle(.borderless)
            }
            if !rows.isEmpty {
                Button("Clear path history", role: .destructive) { store.clearPaths(keyHex) }
            }
        } header: {
            Text("Known paths")
        }
    }

    @ViewBuilder private var traceSection: some View {
        if let c = store.liveContact(keyHex) {
            Section {
                Button(tracing ? "Tracing…" : "Run trace") {
                    Task {
                        tracing = true
                        traced = await store.trace(keyHex)
                        tracing = false
                    }
                }
                .disabled(tracing || !Routing.companion.canTrace(contact: c) || !store.isReady)
                if tracing { HStack { ProgressView(); Text("Waiting for trace…") } }
                if let traced { tracedRows(traced) }
            } header: {
                Text("Trace")
            } footer: {
                Text(Routing.companion.traceHint(contact: c))
            }
        }
    }

    /// The trace's lines, where each hop is (or why it can't be placed —
    /// an ambiguous hop is never pinned to a guess), and Use traced path.
    @ViewBuilder private func tracedRows(_ t: Routing.TraceView) -> some View {
        ForEach(t.lines, id: \.self) { Text($0).font(.system(.callout, design: .monospaced)) }
        if let summary = t.summary { Text(summary).font(.caption).foregroundStyle(.secondary) }
        ForEach(t.placements, id: \.self) { Text($0).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary) }
        if !t.hops.isEmpty {
            Button("Use traced path") {
                hops = t.hops
                mode = Routing.companion.MANUAL
            }
        }
    }

    // MARK: Actions

    private func choose(_ m: RoutingMode) {
        mode = m
        guard m != Routing.companion.MANUAL else { return } // Manual waits for a path.
        run { await store.applyRouting(keyHex, mode: m, pathHex: nil) }
    }

    private func applyManual() {
        guard let hex = HopSelection.shared.toHex(hops: hops, hashWidth: width) else {
            message = Routing.companion.UNUSABLE_HOPS
            return
        }
        run { await store.applyRouting(keyHex, mode: Routing.companion.MANUAL, pathHex: hex) }
    }

    private func use(_ row: PathRow) {
        guard let bytes = HexKt.hexToBytesOrNull(hex: row.pathHex) else { return }
        hops = HopSelection.shared.fromPath(path: bytes, hashWidth: width, contactsByKeyHex: store.contactNames)
        mode = Routing.companion.MANUAL
        run { await store.applyRouting(keyHex, mode: Routing.companion.MANUAL, pathHex: row.pathHex) }
    }

    private func useTokens() {
        guard let parsed = HopSelection.shared.fromTokens(input: tokens, hashWidth: width), !parsed.isEmpty,
              let bytes = HopSelection.shared.toBytes(hops: parsed, hashWidth: width) else {
            message = Routing.companion.tokensRefused(meshWidth: width)
            return
        }
        hops = HopSelection.shared.fromPath(path: bytes, hashWidth: width, contactsByKeyHex: store.contactNames)
    }

    private func run(_ action: @escaping () async -> String) {
        busy = true
        Task {
            message = await action()
            // Manual keeps what the person built; the radio re-reads the
            // contact asynchronously, so its stored route lags this answer.
            if mode != Routing.companion.MANUAL { hops = store.storedHops(keyHex) }
            busy = false
        }
    }

    private func worked(_ row: PathRow) -> String? {
        guard row.lastWorkedAt > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(row.lastWorkedAt) / 1000).formatted(date: .abbreviated, time: .shortened)
    }
}

private extension Binding where Value == Bool {
    /// Run [action] after the value changes (Toggle has no onChange of its own).
    func onChange(_ action: @escaping () -> Void) -> Binding<Bool> {
        Binding(get: { wrappedValue }, set: { wrappedValue = $0; action() })
    }
}
