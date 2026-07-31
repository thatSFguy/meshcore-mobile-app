// Contacts/nodes list with detail sheet (pubkey, location, actions).

import SwiftUI

struct NodesView: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var detail: UiContact?

    var body: some View {
        NavigationStack {
            List {
                section(title: "Contacts", type: 1)
                section(title: "Repeaters", type: 2)
                section(title: "Room servers", type: 3)
                section(title: "Sensors", type: 4)
            }
            .overlay {
                if store.contacts.isEmpty {
                    ContentUnavailableView(
                        "No contacts yet",
                        systemImage: "dot.radiowaves.left.and.right",
                        description: Text("Contacts appear when nearby nodes advertise.")
                    )
                }
            }
            .navigationTitle("Nodes")
            .sheet(item: $detail) { contact in
                ContactDetailSheet(contact: contact)
                    .presentationDetents([.medium])
            }
        }
    }

    @ViewBuilder
    private func section(title: String, type: Int32) -> some View {
        let group = store.contacts.filter { $0.type == type }
        if !group.isEmpty {
            Section(title) {
                ForEach(group) { contact in
                    Button {
                        detail = contact
                    } label: {
                        VStack(alignment: .leading) {
                            Text(contact.name.isEmpty ? String(contact.id.prefix(12)) : contact.name)
                            Text(contact.id.prefix(16) + "…")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

struct ContactDetailSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    let contact: UiContact

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(contact.name.isEmpty ? "Unnamed node" : contact.name)
                .font(.title2)
            Text("Public key").font(.caption).foregroundStyle(.secondary)
            Text(contact.id)
                .font(.system(.caption2, design: .monospaced))
                .textSelection(.enabled)
            if let lat = contact.latitude, let lon = contact.longitude {
                Text("Location").font(.caption).foregroundStyle(.secondary)
                Text(String(format: "%.5f, %.5f", lat, lon))
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
