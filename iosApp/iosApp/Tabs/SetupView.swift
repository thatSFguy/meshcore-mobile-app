// First-run setup (Android's SetupScreen): a checklist, not a wizard.
// The steps and their words are the shared SetupChecklist, so both apps
// say the same thing; each step offers the one action that does it.

import SwiftUI
import Shared

struct SetupView: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var name = ""
    @State private var presets = false
    @State private var scanning = false
    @State private var connecting = false

    private var matching: [String] {
        guard store.isReady, let info = node.selfInfo else { return [] }
        return RadioPresets.shared.matching(frequencyKhz: info.freqKhz, bandwidthHz: info.bwHz,
                                            spreadingFactor: info.sf, codingRate: info.cr).map { $0.name }
    }

    private var steps: [SetupChecklist.Step] {
        SetupChecklist.shared.steps(connected: store.isReady, connectionLabel: store.linkTarget,
                                    nodeName: store.selfName, matchingPresets: matching,
                                    simple: store.isSimple, inAGroup: store.inAGroup)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(SetupChecklist.shared.INTRO).font(.callout)
                }
                Section {
                    modeRow(simple: true, SetupChecklist.shared.MODE_SIMPLE)
                    modeRow(simple: false, SetupChecklist.shared.MODE_FULL)
                } header: {
                    Text(SetupChecklist.shared.MODE_QUESTION)
                } footer: {
                    Text(SetupChecklist.shared.MODE_NOTE)
                }
                ForEach(steps, id: \.key) { step in
                    Section {
                        VStack(alignment: .leading, spacing: 4) {
                            Text((step.done ? "✓ " : "\(step.number). ") + step.title)
                                .font(.headline)
                                .foregroundStyle(step.done ? Color.accentColor : .primary)
                            Text(step.detail).font(.caption).foregroundStyle(.secondary)
                        }
                        if !step.done { action(step.key) }
                    }
                }
                Section {
                    Button(SetupChecklist.shared.finishLabel(connected: store.isReady)) { store.setupComplete = true }
                        .bold()
                }
            }
            .navigationTitle(SetupChecklist.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $presets) { PresetSheet().environmentObject(node) }
            .sheet(isPresented: $scanning) { QrScannerSheet { text in store.openScanned(text) } }
            // The confirmation for what was scanned has to show here: the
            // tabs, where it normally appears, are behind this screen.
            .sheet(isPresented: Binding(get: { store.pendingLink != nil && !scanning },
                                        set: { if !$0 { store.pendingLink = nil } })) {
                if let link = store.pendingLink { LinkConfirmSheet(link: link) }
            }
            .sheet(isPresented: $connecting) {
                NavigationStack {
                    List { BluetoothSection(scanner: store.scanner) }
                        .navigationTitle("Connect a radio")
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { connecting = false } } }
                }
            }
            .onAppear { name = store.selfName }
            .onChange(of: store.selfName) { _, now in if name.isEmpty { name = now } }
        }
    }

    private func modeRow(simple: Bool, _ label: String) -> some View {
        Button { store.setSimple(simple) } label: {
            HStack {
                Image(systemName: store.isSimple == simple ? "largecircle.fill.circle" : "circle")
                Text(label).foregroundStyle(.primary)
            }
        }
    }

    @ViewBuilder private func action(_ key: String) -> some View {
        switch key {
        case SetupChecklist.shared.CONNECT:
            Button("Find a radio over Bluetooth…") { connecting = true }
        case SetupChecklist.shared.NAME:
            if store.isReady {
                TextField("Node name", text: $name)
                Button("Set name") { node.setName(name) }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name == store.selfName)
            } else {
                Text(SetupChecklist.shared.NEEDS_RADIO).foregroundStyle(.secondary)
            }
        case SetupChecklist.shared.RADIO:
            if store.isReady {
                Button("Pick a regional preset…") { presets = true }
                Text(RadioPresets.shared.REGULATORY_CAVEAT).font(.caption).foregroundStyle(.secondary)
            } else {
                Text(SetupChecklist.shared.NEEDS_RADIO).foregroundStyle(.secondary)
            }
        case SetupChecklist.shared.GROUP:
            if store.isReady {
                Button("Scan the channel code") { scanning = true }
            } else {
                Text(SetupChecklist.shared.NEEDS_RADIO).foregroundStyle(.secondary)
            }
        default:
            Text("The full list is in Settings → About.").font(.caption).foregroundStyle(.secondary)
        }
    }
}
