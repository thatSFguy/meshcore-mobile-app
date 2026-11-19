// Settings, hub-and-spoke like Android (iOS Phase 3): the tiles and
// their order come from the shared `settingsGroups()`, so both apps put
// the same thing in the same place; live subtitles use the same shared
// wording functions. Node spokes live in NodeSpokes.swift.

import SwiftUI
import Shared

struct SettingsView: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var confirmReboot = false

    private let groups = SettingsHubModelKt.settingsGroups()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { store.setSimple(!store.isSimple) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(store.isSimple ? AppModes.shared.SIMPLE_TITLE_ON : AppModes.shared.SIMPLE_TITLE_OFF)
                                .foregroundStyle(.primary)
                            Text(store.isSimple ? AppModes.shared.SIMPLE_HINT_ON : AppModes.shared.SIMPLE_HINT_OFF)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                ForEach(groups, id: \.title) { group in
                    Section(group.title) {
                        ForEach(group.tiles.filter { AppModes.shared.showsSettingsTile(mode: store.appMode, route: $0.route) }, id: \.route) { tile in
                            if Self.androidOnly.contains(tile.route) {
                                tileLabel(tile, subtitle: "On Android only, for now")
                                    .foregroundStyle(.secondary)
                            } else {
                                NavigationLink { destination(tile.route) } label: {
                                    tileLabel(tile, subtitle: subtitle(for: tile))
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Reboot radio…", role: .destructive) { confirmReboot = true }
                            .disabled(!store.isReady)
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .alert("Reboot radio?", isPresented: $confirmReboot) {
                Button("Reboot", role: .destructive) { node.reboot() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The connection will drop and re-establish once the radio is back up.")
            }
        }
    }

    /// Firmware updates are not ported (the user's call, 2026-11-18).
    static let androidOnly: Set<String> = ["firmware"]

    private func tileLabel(_ tile: SettingsTile, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(tile.title)
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(subtitle.hasPrefix("⚠") ? Color.red : Color.secondary)
        }
    }

    private func subtitle(for tile: SettingsTile) -> String {
        let info = node.selfInfo
        switch tile.route {
        case "connection":
            return store.linkTarget.map { "\(store.engineStateLabel) · \($0)" } ?? store.engineStateLabel
        case "transports":
            return store.tcpEnabled ? "⚠ Bluetooth and TCP (unencrypted)" : "Bluetooth"
        case "identity":
            return store.isReady ? SettingsHubModelKt.identitySubtitle(name: info?.name) : "Connect to a radio"
        case "radio":
            return SettingsHubModelKt.radioSubtitle(
                freqKhz: info.map { KotlinLong(value: $0.freqKhz) },
                sf: info.map { KotlinInt(int: $0.sf) },
                txPowerDbm: info.map { KotlinInt(int: $0.txPowerDbm) }
            )
        case "channels":
            return SettingsHubModelKt.channelsSubtitle(count: Int32(store.channels.count))
        case "blocking":
            return SettingsHubModelKt.blockingSubtitle(count: Int32(store.blockedKeys.count + store.filteredNames.count))
        case "data":
            return "Keep messages: " + store.retentionPolicy.label()
        default:
            return tile.needsRadio && !store.isReady ? "Connect to a radio" : tile.subtitle
        }
    }

    @ViewBuilder
    private func destination(_ route: String) -> some View {
        switch route {
        case "connection": ConnectionSpoke()
        case "transports": TransportsSpoke()
        case "pin": PinSpoke()
        case "identity": IdentitySpoke()
        case "radio": RadioSpoke()
        case "clock": ClockSpoke()
        case "policies": PoliciesSpoke()
        case "autoadd": AutoAddSpoke()
        case "customvars": CustomVarsSpoke()
        case "channels": ChannelsSpoke()
        case "blocking": BlockingSpoke()
        case "app": AppSpoke()
        case "backup": BackupSpoke()
        case "data": DataSpoke()
        case "about": AboutSpoke()
        default: Text("Not on iOS yet.").foregroundStyle(.secondary)
        }
    }
}

// MARK: - Radio link

private struct ConnectionSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var savedNote: String?

    private var savedSection: some View {
        Section {
            ForEach(store.savedRadios, id: \.key) { node in
                Button {
                    savedNote = store.connectSaved(node)
                } label: {
                    HStack {
                        Text(SavedNodes.shared.label(node: node)).foregroundStyle(.primary)
                        Spacer()
                        if store.isCurrent(node) {
                            Text(store.isReady ? "Connected" : "Current").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .swipeActions {
                    Button("Forget", role: .destructive) { store.forgetSaved(node) }
                }
            }
            if let savedNote { Text(savedNote).font(.caption).foregroundStyle(.secondary) }
        } header: {
            Text("Saved radios")
        } footer: {
            Text("Tap to switch. Swipe to forget — the app then stops reconnecting to it.")
        }
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Status", value: store.engineStateLabel)
                if let target = store.linkTarget {
                    LabeledContent("Radio", value: target)
                }
                if store.isPlaintextLink {
                    Text("⚠ This link is UNENCRYPTED (TCP)")
                        .foregroundStyle(.red)
                        .font(.caption)
                }
                if store.isLinked {
                    Button("Disconnect", role: .destructive) { store.disconnect() }
                }
                Toggle("Reconnect automatically", isOn: $store.autoReconnect)
            }
            if !store.radioFacts.isEmpty {
                Section("This radio") {
                    ForEach(store.radioFacts.indices, id: \.self) { i in
                        LabeledContent(store.radioFacts[i].label, value: store.radioFacts[i].value)
                    }
                    Button("Refresh battery") { store.refreshBattery() }
                        .disabled(!store.isReady)
                }
            }
            if !store.savedRadios.isEmpty { savedSection }
            BluetoothSection(scanner: store.scanner)
            if store.tcpEnabled {
                Section {
                    LabeledContent("Host", value: "\(store.tcpHost):\(store.tcpPort)")
                    Button("Connect (unencrypted)") { store.connectTcp() }
                } header: {
                    Text("Network radio")
                } footer: {
                    Text("Change the address under Transports.")
                }
            }
        }
        .navigationTitle("Connection")
    }
}

private struct TransportsSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var showTcpWarning = false

    var body: some View {
        Form {
            Section {
                LabeledContent("Bluetooth", value: "Always on")
            } footer: {
                Text("iPhones have no USB serial for radios, so Bluetooth is the everyday link.")
            }
            Section {
                Toggle("TCP transport (unencrypted)", isOn: Binding(
                    get: { store.tcpEnabled },
                    set: { wanted in
                        if wanted && !store.tcpWarningAccepted {
                            showTcpWarning = true
                        } else {
                            store.tcpEnabled = wanted
                        }
                    }
                ))
                if store.tcpEnabled {
                    TextField("Host", text: $store.tcpHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Port", value: $store.tcpPort, format: .number.grouping(.never))
                        .keyboardType(.numberPad)
                }
            } header: {
                Text("Network radio")
            } footer: {
                Text("Only for a base-station radio on a network you control.")
            }
        }
        .navigationTitle("Transports")
        .alert("Enable TCP transport?", isPresented: $showTcpWarning) {
            Button("I understand the risk — enable", role: .destructive) {
                store.tcpWarningAccepted = true
                store.tcpEnabled = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The MeshCore TCP link is UNENCRYPTED and UNAUTHENTICATED. Message text " +
                 "and repeater login passwords cross the network in the clear, and anyone " +
                 "who can reach the radio's IP and port can drive your radio. Only use it " +
                 "on a trusted network you control.")
        }
    }
}

// MARK: - Messaging

private struct ChannelsSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var adding = false
    @State private var info: Int32?

    var body: some View {
        List {
            Section {
                if store.channels.isEmpty {
                    Text(store.isReady ? "No channels on this radio." : "Connect to a radio to see its channels.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.channels) { ch in
                    Button {
                        info = ch.id
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ch.name.isEmpty ? "Channel \(ch.id)" : ch.name).foregroundStyle(.primary)
                            Text(store.channelSubtitle(ch.id)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: {
                Text("Channels are obfuscated (AES-ECB, 2-byte MAC), not secure — anyone with the key reads everything.")
            }
        }
        .navigationTitle("Channels")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .disabled(!store.isReady)
            }
        }
        .sheet(isPresented: $adding) { AddChannelSheet() }
        .sheet(item: Binding(get: { info.map(ChannelRef.init) }, set: { info = $0?.id })) { ref in
            ChannelInfoSheet(index: ref.id)
        }
    }

    private struct ChannelRef: Identifiable { let id: Int32 }
}

private struct BlockingSpoke: View {
    @EnvironmentObject var store: MeshCoreStore

    var body: some View {
        List {
            Section {
                if store.blockedKeys.isEmpty && store.filteredNames.isEmpty {
                    Text("Nobody is blocked. Block someone from their contact page, or hide a channel name from a message.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.blockedKeys, id: \.self) { key in
                    HStack {
                        Text(store.title(for: "dm|\(key)"))
                        Spacer()
                        Button("Unblock") { store.setBlocked(key, false) }
                    }
                }
                ForEach(store.filteredNames, id: \.self) { name in
                    HStack {
                        Text("“\(name)” on channels")
                        Spacer()
                        Button("Show") { store.setFiltered(name, false) }
                    }
                }
            } footer: {
                Text("Channel names are not authenticated — hiding a name stops a spammer who keeps it, not a person.")
            }
        }
        .navigationTitle("Blocked senders")
    }
}

// MARK: - App

private struct AppSpoke: View {
    @EnvironmentObject var store: MeshCoreStore

    var body: some View {
        Form {
            Section {
                Toggle("Message notifications", isOn: $store.notificationsEnabled)
                if store.notificationsEnabled {
                    Toggle("Direct messages", isOn: $store.notifyDirect)
                    Toggle("Channel messages", isOn: $store.notifyChannels)
                }
            } header: {
                Text("Notifications")
            } footer: {
                Text("iOS may also need notifications allowed for this app in the system Settings.")
            }
            Section {
                Text("Message history is stored on this phone only, encrypted by iOS (Data Protection) and excluded from backups.")
                Text("The map loads Apple Maps tiles; that is the app's only outbound connection. Nothing else in this app contacts a server, and it works in airplane mode.")
            } header: {
                Text("Privacy and network")
            }
            .font(.callout)
            DiagnosticsSection()
        }
        .navigationTitle("Appearance and alerts")
    }
}

private struct DataSpoke: View {
    @EnvironmentObject var store: MeshCoreStore

    var body: some View {
        Form {
            Section {
                Picker("Keep messages", selection: $store.retentionEncoded) {
                    ForEach(Retention.shared.PRESETS, id: \.self) { p in
                        Text(p.label()).tag(p.encode())
                    }
                }
            } header: {
                Text("Message history")
            } footer: {
                Text("Older messages are deleted from this phone. Age is when they arrived here, not the sender's clock, which may be wrong.")
            }
        }
        .navigationTitle("Data and storage")
    }
}

private struct AboutSpoke: View {
    var body: some View {
        Form {
            Section {
                LabeledContent("App", value: "MeshCore Hardened")
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
            }
            Section {
                Text("No servers, no accounts, no analytics. Message history stays on this phone, encrypted by iOS and excluded from backups. " +
                     "Channels are obfuscated (AES-ECB, 2-byte MAC), not secure.")
                    .font(.callout)
            }
        }
        .navigationTitle("About")
    }
}

/// The scanner is owned by the store (it holds the app's one BLE
/// central); this view only observes it.
struct BluetoothSection: View {
    @EnvironmentObject var store: MeshCoreStore
    @ObservedObject var scanner: BleScanner

    var body: some View {
        Section {
            if let problem = scanner.problem {
                Text(problem).font(.caption).foregroundStyle(.red)
            }
            Button(scanner.isScanning ? "Scanning…" : "Scan for radios") {
                store.scanForRadios()
            }
            .disabled(scanner.isScanning)

            ForEach(scanner.radios) { radio in
                Button {
                    store.connectBle(radio)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(radio.name)
                        Text("signal \(radio.rssi) dBm")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if scanner.isScanning && scanner.radios.isEmpty {
                Text("No MeshCore radios yet. They advertise every few seconds — give it a moment, and check the radio is powered on and not already paired to another phone.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Bluetooth radios")
        } footer: {
            if store.isLinked {
                Text("Scanning disconnects the current radio.")
            }
        }
    }
}

/// Off by default (SCOPE.md). On: radio traffic, link changes and send
/// attempts are recorded, redacted, in memory and in the device log.
private struct DiagnosticsSection: View {
    @AppStorage(DiagnosticsLog.enabledKey) private var enabled = false

    var body: some View {
        Section {
            Toggle("Record diagnostics", isOn: $enabled)
            if enabled {
                NavigationLink("View log") { DiagnosticsView() }
            }
        } header: {
            Text("Diagnostics")
        } footer: {
            Text("For troubleshooting. Keys, passwords and channel secrets are removed; message text is never recorded. Kept in memory only, and also readable by a computer this phone is plugged into.")
        }
    }
}

private struct DiagnosticsView: View {
    @ObservedObject private var diag = DiagnosticsLog.shared
    @State private var saved: String?

    var body: some View {
        ScrollViewReader { proxy in
            List {
                if diag.lines.isEmpty {
                    Text("Nothing yet. Connect to a radio or send a message.")
                        .foregroundStyle(.secondary)
                }
                if let saved {
                    Text("Saved as \(saved) in this app's folder, where a computer the phone is plugged into can copy it.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(Array(diag.lines.enumerated()), id: \.offset) { i, line in
                    Text(line)
                        .font(.system(.caption2, design: .monospaced))
                        .textSelection(.enabled)
                        .id(i)
                }
            }
            .onAppear { proxy.scrollTo(diag.lines.count - 1, anchor: .bottom) }
        }
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Copy") { UIPasteboard.general.string = diag.lines.joined(separator: "\n") }
                    .disabled(diag.lines.isEmpty)
                Button("Save") { saved = diag.saveForComputer() }
                    .disabled(diag.lines.isEmpty)
                Button("Clear", role: .destructive) { diag.clear() }
                    .disabled(diag.lines.isEmpty)
            }
        }
    }
}
