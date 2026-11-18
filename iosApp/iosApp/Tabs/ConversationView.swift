// One thread — DM ("dm|<pubkeyhex>") or channel ("ch|<idx>"). Channel
// threads carry the "obfuscated, not secure" caption per SCOPE.md.
//
// Outgoing bubbles state what is actually known about delivery, and no
// more: a DM is Delivered only on the recipient's ACK; a channel post
// has no ACK at all, so "Sent" (the radio took it) is its best state.

import SwiftUI
import Shared

struct ConversationView: View {
    @EnvironmentObject var store: MeshCoreStore
    let threadKey: String
    @State private var draft = ""
    @State private var showChannelInfo = false
    @State private var hideName: String?
    @State private var info: UiMessage?

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
                            MessageBubble(message: message, isChannel: isChannel,
                                          showsSignal: AppModes.shared.showsSignalDetail(mode: store.appMode))
                                .id(message.id)
                                .contextMenu {
                                    if store.isReady {
                                        Menu("React") {
                                            ForEach(store.quickReactions, id: \.self) { emoji in
                                                Button(emoji) { store.react(to: message.id, with: emoji) }
                                            }
                                        }
                                    }
                                    Button("Copy") { UIPasteboard.general.string = message.text }
                                    if AppModes.shared.showsTechnicalActions(mode: store.appMode) {
                                        Button("Info") { info = message }
                                    }
                                    if isChannel, !message.outgoing, let name = message.senderName, !name.isEmpty {
                                        Button("Hide messages from \(name)") { hideName = name }
                                    }
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
        .sheet(item: $info) { m in MessageInfoSheet(message: m) }
        .toolbar {
            if isChannel, let idx = Int32(threadKey.dropFirst(3)) {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showChannelInfo = true } label: { Image(systemName: "info.circle") }
                        .sheet(isPresented: $showChannelInfo) { ChannelInfoSheet(index: idx) }
                }
            }
        }
        .confirmationDialog(
            "Hide messages from \(hideName ?? "")?",
            isPresented: Binding(get: { hideName != nil }, set: { if !$0 { hideName = nil } }),
            titleVisibility: .visible
        ) {
            Button("Hide on every channel") {
                if let n = hideName { store.setFiltered(n, true) }
                hideName = nil
            }
        } message: {
            Text(BlockList.shared.CHANNEL_FILTER_CAVEAT)
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
    /// Hops and signal under the bubble — hidden in Simple mode (AppModes).
    var showsSignal: Bool = true

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
        if message.outgoing && message.attempts > 1 { parts.append("try \(message.attempts)") }
        if showsSignal, let footer = message.footer { parts.append(footer) }
        if !message.outgoing && message.copies > 1 { parts.append("×\(message.copies)") }
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

/// How one message travelled: direction, time, delivery state and the
/// route — "Arrived via" for what we received, "Repeated by" for what we
/// sent. Wording is the shared MessageDetail, as on Android.
private struct MessageInfoSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let message: UiMessage

    var body: some View {
        let m = message.source
        let route = store.route(for: m)
        NavigationStack {
            List {
                Section {
                    LabeledContent("Direction", value: m.outgoing ? "Sent" : "Received")
                    LabeledContent("Time", value: Date(timeIntervalSince1970: TimeInterval(m.timestamp))
                        .formatted(date: .abbreviated, time: .standard))
                    if m.outgoing {
                        LabeledContent("State", value: MessageDetail.shared.stateLabel(status: m.status))
                        if m.attempts > 0 { LabeledContent("Attempts", value: "\(m.attempts)") }
                    } else {
                        if let snr = MessageSignal.shared.info(snr: m.snr, hops: m.hops) {
                            LabeledContent("SNR", value: snr)
                        }
                        if let arrival = MessageDetail.shared.arrivalLabel(hops: m.hops) {
                            LabeledContent("Arrival", value: arrival)
                        }
                    }
                }
                if let copies = MessageDetail.shared.copiesLabel(copies: m.copies) {
                    Section {
                        Text(copies)
                    } footer: {
                        Text(MessageDetail.shared.COPIES_EXPLAINED)
                    }
                }
                if !m.outgoing,
                   let sketch = store.arrivalSketch(m, senderLabel: m.senderName ?? store.title(for: m.threadKey)) {
                    PathSketchMap(title: "Route on map", sketch: sketch)
                }
                Section(route.title) {
                    Text(route.summary).font(.callout)
                    ForEach(route.steps, id: \.self) { step in
                        Text(step)
                            .font(.system(.callout, design: .monospaced))
                            .foregroundStyle(step.hasPrefix("↑") || step.hasPrefix("↓") ? .secondary : .primary)
                    }
                }
            }
            .navigationTitle("Message info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
