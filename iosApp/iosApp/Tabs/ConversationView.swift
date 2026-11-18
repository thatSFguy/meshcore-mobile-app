// One thread — DM ("dm|<pubkeyhex>") or channel ("ch|<idx>"). Channel
// threads carry the "obfuscated, not secure" caption per SCOPE.md.
//
// Outgoing bubbles state what is actually known about delivery, and no
// more: a DM is Delivered only on the recipient's ACK; a channel post
// has no ACK at all, so "Sent" (the radio took it) is its best state.

import SwiftUI

struct ConversationView: View {
    @EnvironmentObject var store: MeshCoreStore
    let threadKey: String
    @State private var draft = ""
    @State private var showChannelInfo = false

    private var isChannel: Bool { threadKey.hasPrefix("ch|") }

    private var messages: [UiMessage] {
        _ = store.bookRevision // re-read on every book change
        return store.messages(in: threadKey)
    }

    var body: some View {
        VStack(spacing: 0) {
            if isChannel, let idx = Int32(threadKey.dropFirst(3)) {
                Text(store.channelSubtitle(idx))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 2)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(messages) { message in
                            MessageBubble(message: message, isChannel: isChannel)
                                .id(message.id)
                                .contextMenu {
                                    Button("Copy") { UIPasteboard.general.string = message.text }
                                    Button("Delete", role: .destructive) { store.deleteMessage(message.id) }
                                }
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .onChange(of: messages.last?.id) {
                    if let last = messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
                .onAppear {
                    if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            HStack {
                TextField(isChannel ? "Message channel…" : "Message…", text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
                Button {
                    send()
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(!store.isReady || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(8)
        }
        .navigationTitle(store.title(for: threadKey))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isChannel, let idx = Int32(threadKey.dropFirst(3)) {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showChannelInfo = true } label: { Image(systemName: "info.circle") }
                        .sheet(isPresented: $showChannelInfo) { ChannelInfoSheet(index: idx) }
                }
            }
        }
        .onAppear { store.setActiveThread(threadKey) }
        .onDisappear { store.setActiveThread(nil) }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if isChannel, let idx = Int32(threadKey.dropFirst(3)) {
            store.sendChannelMessage(index: idx, text: text)
        } else {
            store.sendDirectMessage(peerHex: String(threadKey.dropFirst(3)), text: text)
        }
        draft = ""
    }
}

struct MessageBubble: View {
    let message: UiMessage
    let isChannel: Bool

    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 2) {
                if let sender = message.senderName, !message.outgoing {
                    Text(sender).font(.caption2).foregroundStyle(.tint)
                }
                Text(message.text).textSelection(.enabled)
                if !message.reactions.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(message.reactions, id: \.emoji) { r in
                            Text(r.count > 1 ? "\(r.emoji) \(r.count)" : r.emoji)
                                .font(.caption)
                                .padding(.horizontal, 4)
                                .background(.quaternary, in: Capsule())
                        }
                    }
                }
                HStack(spacing: 4) {
                    Text(Date(timeIntervalSince1970: TimeInterval(message.timestamp)), style: .time)
                    if let detail { Text("· \(detail)") }
                    if message.outgoing { StatusMark(status: message.status, isChannel: isChannel) }
                }
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

    /// Hops, signal and retries — the facts the row has, briefly.
    private var detail: String? {
        var parts: [String] = []
        if !message.outgoing {
            if let h = message.hops { parts.append(h == 0 ? "direct" : "\(h) hop\(h == 1 ? "" : "s")") }
            if let s = message.snr { parts.append(String(format: "%.1f dB", s)) }
            if message.copies > 1 { parts.append("×\(message.copies)") }
        } else if message.attempts > 1 {
            parts.append("try \(message.attempts)")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

private struct StatusMark: View {
    let status: UiStatus
    let isChannel: Bool

    var body: some View {
        switch status {
        case .pending:
            Image(systemName: "clock").accessibilityLabel("Sending")
        case .sent:
            // A channel post has no ACK: Sent is as good as it gets, so
            // it must not read as "waiting".
            Image(systemName: "checkmark")
                .accessibilityLabel(isChannel ? "Sent" : "Sent, not yet acknowledged")
        case .delivered:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .accessibilityLabel("Delivered")
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
                .accessibilityLabel("Not delivered")
        }
    }
}
