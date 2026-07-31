// Merged conversation list: channels + DM threads (Phase-1: channels
// come live from the engine; DM rows appear once a thread has traffic).

import SwiftUI

struct ChatsView: View {
    @EnvironmentObject var store: MeshCoreStore

    var body: some View {
        NavigationStack {
            List {
                if !store.isReady {
                    Text("Not connected to a radio.\nConnect in Settings.")
                        .foregroundStyle(.secondary)
                }
                Section("Channels") {
                    ForEach(store.channels) { channel in
                        NavigationLink(value: "ch|\(channel.id)") {
                            Label(channel.name.isEmpty ? "Channel \(channel.id)" : channel.name,
                                  systemImage: "number")
                        }
                    }
                }
                Section("Direct messages") {
                    ForEach(store.contacts.filter { $0.type == 1 }) { contact in
                        NavigationLink(value: "dm|\(contact.id)") {
                            Label(contact.name.isEmpty ? String(contact.id.prefix(12)) : contact.name,
                                  systemImage: "person")
                        }
                    }
                }
            }
            .navigationTitle("Chats")
            .navigationDestination(for: String.self) { key in
                ConversationView(threadKey: key)
            }
        }
    }
}
