// One thread — DM ("dm|<pubkeyhex>") or channel ("ch|<idx>"). Channel
// threads carry the "obfuscated, not secure" caption per SCOPE.md.

import SwiftUI

struct ConversationView: View {
    @EnvironmentObject var store: MeshCoreStore
    let threadKey: String
    @State private var draft = ""

    private var isChannel: Bool { threadKey.hasPrefix("ch|") }

    var body: some View {
        VStack(spacing: 0) {
            if isChannel {
                Text("Channel crypto is obfuscation, not security")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 2)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(store.threads[threadKey] ?? []) { message in
                            MessageBubble(message: message)
                                .id(message.id)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .onChange(of: store.threads[threadKey]?.count ?? 0) {
                    if let last = store.threads[threadKey]?.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
            HStack {
                TextField(isChannel ? "Message channel…" : "Message…", text: $draft)
                    .textFieldStyle(.roundedBorder)
                Button {
                    let text = draft.trimmingCharacters(in: .whitespaces)
                    guard !text.isEmpty else { return }
                    if isChannel, let idx = Int32(threadKey.dropFirst(3)) {
                        store.sendChannelMessage(index: idx, text: text)
                    } else {
                        store.sendDirectMessage(peerHex: String(threadKey.dropFirst(3)), text: text)
                    }
                    draft = ""
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(8)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var title: String {
        if isChannel {
            let idx = Int32(threadKey.dropFirst(3)) ?? -1
            return store.channels.first { $0.id == idx }.map { "# \($0.name)" } ?? "Channel"
        }
        let hex = String(threadKey.dropFirst(3))
        return store.contacts.first { $0.id == hex }?.name ?? String(hex.prefix(12))
    }
}

struct MessageBubble: View {
    let message: UiMessage

    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 2) {
                if let sender = message.senderName, !message.outgoing {
                    Text(sender).font(.caption2).foregroundStyle(.tint)
                }
                Text(message.text)
                Text(Date(timeIntervalSince1970: TimeInterval(message.timestamp)),
                     style: .time)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(10)
            .background(
                message.outgoing
                    ? AnyShapeStyle(.tint.opacity(0.2))
                    : AnyShapeStyle(.quaternary),
                in: RoundedRectangle(cornerRadius: 12)
            )
            if !message.outgoing { Spacer(minLength: 48) }
        }
    }
}
