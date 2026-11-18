// Settings: connection (BLE, plus TCP behind the same stern plaintext
// warning as Android), the connected radio, and about.

import SwiftUI
import Shared

struct SettingsView: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var showTcpWarning = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
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

                BluetoothSection(scanner: store.scanner)

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
                        Button("Connect (unencrypted)") { store.connectTcp() }
                    }
                } header: {
                    Text("Network radio")
                } footer: {
                    Text("Only for a base-station radio on a network you control.")
                }

                Section {
                    if store.radioFacts.isEmpty {
                        Text("Connect to a radio to see its settings.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(store.radioFacts.indices, id: \.self) { i in
                            LabeledContent(store.radioFacts[i].label, value: store.radioFacts[i].value)
                        }
                        if !store.selfKeyHex.isEmpty {
                            LabeledContent("Public key") {
                                Text(store.selfKeyHex.prefix(16) + "…")
                                    .font(.system(.caption, design: .monospaced))
                                    .textSelection(.enabled)
                            }
                        }
                        Button("Refresh battery") { store.refreshBattery() }
                            .disabled(!store.isReady)
                    }
                } header: {
                    Text("This radio")
                } footer: {
                    if !store.radioFacts.isEmpty {
                        Text("Radio settings are read-only on iOS for now; change them from the Android app or the radio's own tools.")
                    }
                }

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

                if !store.blockedKeys.isEmpty || !store.filteredNames.isEmpty {
                    Section {
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
                    } header: {
                        Text("Blocked")
                    } footer: {
                        Text("Channel names are not authenticated — hiding a name stops a spammer who keeps it, not a person.")
                    }
                }

                Section("About") {
                    LabeledContent("App", value: "MeshCore Hardened")
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
                    Text("No servers, no accounts, no analytics. Message history stays on this phone, encrypted by iOS and excluded from backups. " +
                         "Channels are obfuscated (AES-ECB, 2-byte MAC), not secure.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
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
}

/// The scanner is owned by the store (it holds the app's one BLE
/// central); this view only observes it.
private struct BluetoothSection: View {
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
