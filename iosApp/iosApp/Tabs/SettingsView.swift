// Settings: connection (TCP first, behind the same stern plaintext
// warning as Android; BLE pending IosBleTransport), node info, about.

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var showTcpWarning = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    LabeledContent("Status", value: store.engineStateLabel)
                    if store.isPlaintextLink {
                        Text("⚠ This link is UNENCRYPTED (TCP)")
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
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
                        TextField("Port", value: $store.tcpPort, format: .number)
                        Button("Connect (unencrypted)") { store.connectTcp() }
                        Button("Disconnect", role: .destructive) { store.disconnect() }
                    }
                    Text("Bluetooth radios: coming with the IosBleTransport port (see iosApp/README.md).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("This node") {
                    if store.selfName.isEmpty {
                        Text("Connect to a radio to see node info.")
                            .foregroundStyle(.secondary)
                    } else {
                        LabeledContent("Name", value: store.selfName)
                        LabeledContent("Public key") {
                            Text(store.selfKeyHex.prefix(16) + "…")
                                .font(.system(.caption, design: .monospaced))
                        }
                    }
                }

                Section("About") {
                    Text("MeshCore companion client — no servers, no accounts, no analytics. " +
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
