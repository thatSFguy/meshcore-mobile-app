// Chats: every thread with traffic, most recent first (the shared
// MessageBook's summaries), then the channels and contacts that have no
// traffic yet so a conversation can be started.

import SwiftUI

struct ChatsView: View {
    @EnvironmentObject var store: MeshCoreStore

    /// Channels and chat contacts not already listed as a thread.
    private var quietChannels: [UiChannel] {
        let active = Set(store.threads.map(\.id))
        return store.channels.filter { !active.contains("ch|\($0.id)") }
    }

    private var quietContacts: [UiContact] {
        let active = Set(store.threads.map(\.id))
        return store.contacts.filter { $0.type == 1 && !active.contains("dm|\($0.id)") }
    }

    var body: some View {
        NavigationStack {
            List {
                if !store.isReady {
                    Text("Not connected to a radio.\nConnect in Settings.")
                        .foregroundStyle(.secondary)
                }
                if let problem = store.historyProblem {
                    Text(problem).font(.caption).foregroundStyle(.red)
                }
                if !store.threads.isEmpty {
                    Section("Recent") {
                        ForEach(store.threads) { thread in
                            NavigationLink(value: thread.id) {
                                ThreadRow(thread: thread, title: store.title(for: thread.id))
                            }
                            .swipeActions {
                                Button("Delete", role: .destructive) {
                                    store.deleteThread(thread.id)
                                }
                            }
                        }
                    }
                }
                if !quietChannels.isEmpty {
                    Section("Channels") {
                        ForEach(quietChannels) { channel in
                            NavigationLink(value: "ch|\(channel.id)") {
                                Label(channel.name.isEmpty ? "Channel \(channel.id)" : channel.name,
                                      systemImage: "number")
                            }
                        }
                    }
                }
                if !quietContacts.isEmpty {
                    Section("Contacts") {
                        ForEach(quietContacts) { contact in
                            NavigationLink(value: "dm|\(contact.id)") {
                                Label(contact.name.isEmpty ? String(contact.id.prefix(12)) : contact.name,
                                      systemImage: "person")
                            }
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

private struct ThreadRow: View {
    let thread: UiThread
    let title: String

    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: thread.id.hasPrefix("ch|") ? "number" : "person")
                .foregroundStyle(.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(title).font(.headline).lineLimit(1)
                    Spacer()
                    if thread.lastAtMs > 0 {
                        Text(Date(timeIntervalSince1970: TimeInterval(thread.lastAtMs) / 1000),
                             style: .relative)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Text(preview).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    Spacer()
                    if thread.unread > 0 {
                        Text(thread.unread > 99 ? "99+" : "\(thread.unread)")
                            .font(.caption2.bold())
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(.tint, in: Capsule())
                            .foregroundStyle(.white)
                    }
                }
            }
        }
    }

    private var preview: String {
        let text = thread.lastText ?? ""
        if thread.lastOutgoing { return "You: \(text)" }
        if let s = thread.lastSender, !s.isEmpty { return "\(s): \(text)" }
        return text
    }
}
