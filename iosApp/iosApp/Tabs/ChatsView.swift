// Chats: every thread with traffic, most recent first (the shared
// MessageBook's summaries), then the channels and contacts that have no
// traffic yet so a conversation can be started.

import SwiftUI
import Shared

struct ChatsView: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var addingChannel = false
    @State private var showMyContact = false
    @State private var newMessage = false
    @State private var pasteNote: String?
    @State private var path: [String] = []

    /// Channels and chat contacts not already listed as a thread.
    private var quietChannels: [UiChannel] {
        let active = Set(store.threads.map(\.id))
        return store.channels.filter { !active.contains("ch|\($0.id)") && store.showsThread("ch|\($0.id)") }
    }

    @State private var query = ""

    /// Threads for the current mode (Simple hides Public and non-people),
    /// narrowed by the search (shared ChatSearch).
    private var shownThreads: [UiThread] {
        store.threads.filter {
            store.showsThread($0.id) &&
                ChatSearch.shared.matches(title: store.title(for: $0.id), preview: $0.lastText, query: query)
        }
    }
    private var pinnedThreads: [UiThread] { shownThreads.filter { store.isPinnedThread($0.id) } }
    private var recentThreads: [UiThread] { shownThreads.filter { !store.isPinnedThread($0.id) } }

    private var quietContacts: [UiContact] {
        let active = Set(store.threads.map(\.id))
        return store.contacts.filter { $0.type == 1 && !active.contains("dm|\($0.id)") }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if !store.isReady {
                    Text("Not connected to a radio.\nConnect in Settings.")
                        .foregroundStyle(.secondary)
                }
                if let pasteNote {
                    Text(pasteNote).font(.caption).foregroundStyle(.secondary)
                }
                if let problem = store.historyProblem {
                    Text(problem).font(.caption).foregroundStyle(.red)
                }
                if !pinnedThreads.isEmpty {
                    Section("Pinned") { ForEach(pinnedThreads) { threadRow($0) } }
                }
                if !recentThreads.isEmpty {
                    Section("Recent") { ForEach(recentThreads) { threadRow($0) } }
                }
                if !query.isEmpty && shownThreads.isEmpty {
                    Text("Nothing matches.").foregroundStyle(.secondary)
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
            .searchable(text: $query, prompt: "Search conversations")
            .toolbar {
                // What + starts: a message or a channel (Android's chooser).
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { newMessage = true } label: { Label("Message a contact", systemImage: "person") }
                        Button { addingChannel = true } label: { Label("Add or join a channel", systemImage: "number") }
                        Button { pasteNote = store.pasteCode() } label: { Label("Paste a code", systemImage: "doc.on.clipboard") }
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Start")
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button { showMyContact = true } label: { Image(systemName: "qrcode") }
                        .accessibilityLabel("My code")
                }
            }
            .sheet(isPresented: $addingChannel) { AddChannelSheet() }
            .sheet(isPresented: $showMyContact) { MyContactSheet() }
            .sheet(isPresented: $newMessage) {
                NewMessageSheet { key in
                    newMessage = false
                    path.append("dm|\(key)")
                }
            }
            .navigationDestination(for: String.self) { key in
                ConversationView(threadKey: key)
            }
        }
    }
}

extension ChatsView {
    func threadRow(_ thread: UiThread) -> some View {
        NavigationLink(value: thread.id) {
            ThreadRow(thread: thread, title: store.title(for: thread.id))
        }
        .swipeActions {
            Button("Delete", role: .destructive) { store.deleteThread(thread.id) }
        }
        .swipeActions(edge: .leading) {
            Button("Unread") { store.markUnread(thread.id) }.tint(.blue)
        }
        .contextMenu {
            Button(store.isPinnedThread(thread.id) ? "Unpin" : "Pin to top") { store.togglePinThread(thread.id) }
            Button("Mark unread") { store.markUnread(thread.id) }
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

/// Start a conversation: people only, last talked-to first (shared
/// NewMessage). Repeaters take no chat and a room needs a sign-in, which
/// is the Nodes tab's job.
private struct NewMessageSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let onPick: (String) -> Void
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                let shown = ContactNodes.shared.messageCandidates(nodes: store.nodes, query: query)
                if shown.isEmpty {
                    Text(NewMessage.shared.emptyText(query: query)).foregroundStyle(.secondary)
                }
                ForEach(shown, id: \.keyHex) { n in
                    Button(store.nickname(n.keyHex) ?? (n.name.isEmpty ? String(n.keyHex.prefix(12)) : n.name)) {
                        onPick(n.keyHex)
                    }
                    .foregroundStyle(.primary)
                }
            }
            .searchable(text: $query, prompt: "Search contacts")
            .navigationTitle("New message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}
