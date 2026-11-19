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
    /// The message a reply quotes (swipe right, or long-press → Reply).
    @State private var replyingTo: UiMessage?
    /// "Message privately": who is about to be asked, and the last outcome.
    /// The message the full reaction picker is open for.
    @State private var reactingTo: UiMessage?
    @State private var askName: String?
    @State private var dmNote: String?
    @State private var showSenders = false
    @State private var roomSignIn = false
    @State private var manageRoom = false
    @State private var clearConfirm = false
    @State private var showContact = false
    /// An http link from a message, held for the leave-the-mesh warning.
    @State private var pendingUrl: URL?
    @State private var showRouting = false
    @Environment(\.dismiss) private var dismiss

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
                                          showsSignal: AppModes.shared.showsSignalDetail(mode: store.appMode),
                                          selfName: store.selfName,
                                          dmLine: isChannel ? store.dmLine(message) : nil,
                                          dmAsk: isChannel ? dmAsk(for: message) : nil)
                                .id(message.id)
                                .modifier(SwipeToReply { replyingTo = message })
                                .contextMenu {
                                    if store.isReady {
                                        // One horizontal row, as Android's quick-reaction
                                        // bar — a submenu listed them vertically.
                                        ControlGroup {
                                            ForEach(store.quickReactions, id: \.self) { emoji in
                                                Button(emoji) { store.react(to: message.id, with: emoji) }
                                            }
                                            // The rest of the wire list, as Android's ＋.
                                            Button { reactingTo = message } label: { Image(systemName: "plus") }
                                        }
                                        .controlGroupStyle(.palette)
                                    }
                                    if message.outgoing && message.status == .failed && store.isReady {
                                        Button("Resend") { store.resend(message.id) }
                                    }
                                    Button("Reply") { replyingTo = message }
                                    if isChannel, store.isReady, store.canAskPrivately(message), let name = message.senderName {
                                        Button("Message privately…") { askName = name }
                                    }
                                    if canMention(message) {
                                        Button("Mention") { mention(message.senderName ?? "") }
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
                // Links in messages: http only after the warning; meshcore://
                // through the same confirmation as a scanned code.
                .environment(\.openURL, OpenURLAction { url in
                    if url.scheme?.lowercased() == "meshcore" {
                        store.openScanned(url.absoluteString)
                    } else {
                        pendingUrl = url
                    }
                    return .handled
                })
                .onChange(of: messages.last?.id) {
                    if let last = messages.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
                .onAppear {
                    if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            if let dmNote {
                Text(dmNote).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 8)
            }
            roomBanner
            if let target = replyingTo { replyBanner(target) }
            mentionSuggestions
            if let counter = ComposerBudget.shared.counter(text: draft, budget: budget) {
                Text(counter).font(.caption2).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing).padding(.horizontal, 12)
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
        .sheet(item: $reactingTo) { m in
            ReactionPicker { emoji in store.react(to: m.id, with: emoji); reactingTo = nil }
                .presentationDetents([.medium, .large])
        }
        .toolbar {
            if isChannel, let idx = Int32(threadKey.dropFirst(3)) {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showChannelInfo = true } label: { Image(systemName: "info.circle") }
                        .sheet(isPresented: $showChannelInfo) { ChannelInfoSheet(index: idx) }
                }
            }
            ToolbarItem(placement: .topBarTrailing) { threadMenu }
        }
        .sheet(isPresented: $showSenders) { NamesSeenSheet(threadKey: threadKey, isChannel: isChannel) }
        .sheet(isPresented: $roomSignIn) {
            if let node = roomNode {
                NavigationStack {
                    RepeaterSignIn(node: node, isRoom: true)
                        .navigationTitle(node.displayName)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { roomSignIn = false } } }
                }
                .onChange(of: store.adminSession(node.id).signedIn) { _, now in if now { roomSignIn = false } }
            }
        }
        .sheet(isPresented: $showContact) { if let n = peerNode { NodeDetailSheet(node: n) } }
        .navigationDestination(isPresented: $showRouting) {
            if let n = peerNode { RoutingView(keyHex: n.id, title: n.displayName) }
        }
        .navigationDestination(isPresented: $manageRoom) {
            if let node = roomNode { RepeaterView(node: node) }
        }
        .alert(MessageLinkSpans.shared.LEAVE_TITLE,
               isPresented: Binding(get: { pendingUrl != nil }, set: { if !$0 { pendingUrl = nil } })) {
            Button(MessageLinkSpans.shared.LEAVE_ACTION) {
                if let u = pendingUrl { UIApplication.shared.open(u) }
                pendingUrl = nil
            }
            Button("Cancel", role: .cancel) { pendingUrl = nil }
        } message: {
            Text(MessageLinkSpans.shared.leaveText(url: pendingUrl?.absoluteString ?? ""))
        }
        .confirmationDialog("Clear this thread?", isPresented: $clearConfirm, titleVisibility: .visible) {
            Button("Clear thread", role: .destructive) {
                store.deleteThread(threadKey)
                dismiss()
            }
        } message: {
            Text("Removes the messages from this phone only — nothing is sent to the mesh.")
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
        .alert("Message \(askName ?? "") privately?", isPresented: Binding(get: { askName != nil }, set: { if !$0 { askName = nil } })) {
            Button("Send request") { sendAsk() }
            Button("Cancel", role: .cancel) { askName = nil }
        } message: {
            Text(DmRequests.companion.CONFIRM_TEXT)
        }
        // The radio can't carry more than the budget, and the frame builder
        // doesn't trim — so the composer stops at it (shared ComposerBudget).
        .onChange(of: draft) { _, now in
            let clamped = ComposerBudget.shared.clamp(text: now, budget: budget)
            if clamped != now { draft = clamped }
        }
        .onChange(of: replyingTo?.id) { _, _ in
            draft = ComposerBudget.shared.clamp(text: draft, budget: budget)
        }
        .onAppear {
            store.setActiveThread(threadKey)
            if draft.isEmpty { draft = store.drafts[threadKey] ?? "" }
        }
        .onDisappear {
            store.setActiveThread(nil)
            store.drafts[threadKey] = draft.isEmpty ? nil : draft
        }
    }

    /// Bytes the draft may use: the radio's limit less the quote's prefix.
    private var budget: Int32 {
        ComposerBudget.shared.budget(isChannel: isChannel, selfName: store.selfName, quotePrefix: quotePrefix())
    }

    // MARK: Thread menu and rooms (as Android's conversation menu)

    private var isRoom: Bool { store.isRoom(threadKey) }

    /// The peer of a direct thread, as a node (nil for a channel, or a key
    /// the radio no longer holds).
    private var peerNode: UiNode? {
        guard threadKey.hasPrefix("dm|") else { return nil }
        let key = String(threadKey.dropFirst(3))
        let now = Int64(Date().timeIntervalSince1970)
        return store.nodes.first { $0.keyHex == key }.map {
            UiNode($0, now: now, nickname: store.nickname(key), distance: store.distance(to: $0))
        }
    }

    private var roomNode: UiNode? {
        guard isRoom else { return nil }
        let key = String(threadKey.dropFirst(3))
        let now = Int64(Date().timeIntervalSince1970)
        return store.nodes.first { $0.keyHex == key }.map { UiNode($0, now: now, nickname: store.nickname(key)) }
    }

    private var threadMenu: some View {
        Menu {
            Toggle("Mute notifications", isOn: Binding(
                get: { store.isMuted(threadKey) },
                set: { _ in store.toggleMute(threadKey) }
            ))
            Button("Mark unread") {
                store.markUnread(threadKey)
                dismiss()
            }
            if isChannel || isRoom {
                Button("Names seen…") { showSenders = true }
            }
            if isRoom, let node = roomNode, store.adminSession(node.id).isAdmin {
                Button("Manage room…") { manageRoom = true }
            }
            // A person's thread reaches their sheet and routes from here, in
            // full mode (Android's "Contact details…" / "Routing / paths…").
            if !isChannel, AppModes.shared.showsTechnicalActions(mode: store.appMode), peerNode != nil {
                Button("Contact details…") { showContact = true }
                Button("Routing / paths…") { showRouting = true }
            }
            Button("Clear thread…", role: .destructive) { clearConfirm = true }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }

    /// Why a room isn't working yet, and the way to fix it (shared RoomModel).
    @ViewBuilder private var roomBanner: some View {
        if isRoom, let node = roomNode {
            let session = store.adminSession(node.id)
            if let notice = RoomModelKt.roomAccessNotice(session: session) {
                HStack(alignment: .top) {
                    Text(notice).font(.caption)
                    Spacer()
                    if let action = RoomModelKt.roomAccessAction(session: session) {
                        Button(action) { roomSignIn = true }.font(.caption.bold())
                    }
                }
                .padding(8)
                .background(Color.orange.opacity(0.15))
            }
        }
    }

    // MARK: Reply and mention (shared Quoting / Mentions, as Android)

    private func replyBanner(_ target: UiMessage) -> some View {
        let who = target.outgoing ? "yourself" : (target.senderName ?? store.title(for: threadKey))
        let body: String = Quoting.shared.bodyPart(text: target.text)
        let snippet: String = Quoting.shared.oneLine(text: body, maxChars: Quoting.shared.QUOTE_MAX_CHARS)
        return HStack {
            Rectangle().fill(.tint).frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text("Replying to \(who)").font(.caption.bold())
                Text(snippet).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button { replyingTo = nil } label: { Image(systemName: "xmark.circle.fill") }
                .foregroundStyle(.secondary)
        }
        .frame(height: 34)
        .padding(.horizontal, 8)
    }

    /// Who can be mentioned here: a channel's recent senders, or the DM peer.
    private var mentionNames: [String] {
        if isChannel || isRoom { return store.senderNames(threadKey) }
        let peer = store.title(for: threadKey)
        return Mentions.shared.canMention(name: peer) ? [peer] : []
    }

    @ViewBuilder private var mentionSuggestions: some View {
        // The composer has no cursor API; the end of the draft is where
        // a mention is being typed.
        let cursor = Int32(draft.utf16.count)
        if let query = Mentions.shared.activeQuery(text: draft, cursor: cursor) {
            let names = Mentions.shared.candidates(query: query, names: mentionNames, limit: 6)
            if !names.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(names, id: \.self) { name in
                            Button("@\(name)") {
                                draft = Mentions.shared.complete(text: draft, cursor: cursor, name: name).text
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.horizontal, 8)
                }
            }
        }
    }

    // MARK: Message privately

    private var channelIndex: Int32? { isChannel ? Int32(threadKey.dropFirst(3)) : nil }

    /// Accept / Decline for a request addressed to us, still unanswered.
    private func dmAsk(for m: UiMessage) -> DmAsk? {
        guard let key = store.dmAsker(m), let idx = channelIndex else { return nil }
        let name = m.senderName ?? ""
        return DmAsk(
            keyPrefix: String(key.prefix(8)),
            onAccept: { Task { dmNote = await store.acceptPrivately(channel: idx, name: name, keyHex: key) } },
            onDecline: { store.declinePrivately(key) }
        )
    }

    private func sendAsk() {
        guard let name = askName, let idx = channelIndex else { return }
        askName = nil
        Task { dmNote = await store.askPrivately(channel: idx, name: name) }
    }

    /// `> Sender: snippet` for the message being replied to, or "".
    private func quotePrefix() -> String {
        guard let target = replyingTo else { return "" }
        let who: String? = target.outgoing ? store.selfName : target.senderName
        let body: String = Quoting.shared.bodyPart(text: target.text)
        return Quoting.shared.prefixFor(senderName: who, text: body)
    }

    private func canMention(_ m: UiMessage) -> Bool {
        isChannel && !m.outgoing && Mentions.shared.canMention(name: m.senderName ?? "")
    }

    /// Insert `@[Name] ` — the name only, not a quote: Reply quotes the
    /// text (bytes out of a ~150-byte frame), Mention costs just the name.
    private func mention(_ name: String) {
        let insert = Mentions.shared.wire(name: name.trimmingCharacters(in: .whitespaces)) + " "
        draft += (draft.isEmpty || draft.hasSuffix(" ") ? "" : " ") + insert
    }

    private func send() {
        let typed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !typed.isEmpty else { return }
        let text = quotePrefix() + typed
        store.drafts[threadKey] = nil
        replyingTo = nil
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
    /// This radio's name, so a mention of it is drawn bold.
    var selfName: String = ""
    /// A "message privately" post, shown as its line instead of wire text.
    var dmLine: String? = nil
    /// Accept / Decline when that post asks us.
    var dmAsk: DmAsk? = nil

    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 2) {
                if let sender = message.senderName, !message.outgoing {
                    Text(sender).font(.caption2).foregroundStyle(.tint)
                }
                if let dmLine {
                    Text(dmLine).italic()
                    if let ask = dmAsk {
                        Text("Their key starts \(ask.keyPrefix)").font(.caption2).foregroundStyle(.secondary)
                        HStack {
                            Button("Decline", action: ask.onDecline)
                            Button("Accept", action: ask.onAccept).bold()
                        }
                        .buttonStyle(.borderless)
                    }
                } else if let quoted = Quoting.shared.quotedPart(text: message.text) {
                    HStack(spacing: 6) {
                        Rectangle().fill(.secondary).frame(width: 2)
                        Text(quoted).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                if dmLine == nil {
                    Text(styled(Quoting.shared.bodyPart(text: message.text))).textSelection(.enabled)
                }
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

    /// `@[Name]` mentions coloured; the one naming this radio in bold.
    private func styled(_ text: String) -> AttributedString {
        var out = AttributedString(text)
        let utf16 = text.utf16
        func range(_ lo: Int, _ hi: Int) -> Range<AttributedString.Index>? {
            guard let l = utf16.index(utf16.startIndex, offsetBy: lo, limitedBy: utf16.endIndex),
                  let h = utf16.index(utf16.startIndex, offsetBy: hi, limitedBy: utf16.endIndex),
                  let a = AttributedString.Index(l, within: out), let b = AttributedString.Index(h, within: out) else { return nil }
            return a..<b
        }
        // Links (shared MessageLinkSpans). Tapping one goes through the
        // conversation's openURL handler, which confirms before anything
        // leaves the mesh — never straight to the browser.
        let links = MessageLinkSpans.shared.find(text: text)
        for link in links {
            if let r = range(Int(link.start), Int(link.end)), let url = URL(string: link.url) {
                out[r].link = url
                out[r].underlineStyle = .single
            }
        }
        let h = Mentions.shared.highlights(text: text, selfName: selfName).map { Int(truncating: $0) }
        var i = 0
        while i + 2 < h.count {
            // A mention inside a link stays part of the link (Android's rule).
            let inLink = links.contains { Int($0.start) <= h[i] && h[i] < Int($0.end) }
            if !inLink, let r = range(h[i], h[i + 1]) {
                out[r].foregroundColor = .accentColor
                if h[i + 2] == 1 { out[r].font = .body.bold() }
            }
            i += 3
        }
        return out
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

/// Swipe a bubble right to reply, as Android: the bubble follows the
/// finger a little and springs back; past the threshold it replies.
private struct SwipeToReply: ViewModifier {
    let onReply: () -> Void
    @State private var offset: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .offset(x: offset)
            .overlay(alignment: .leading) {
                if offset > 20 {
                    Image(systemName: "arrowshape.turn.up.left.fill")
                        .foregroundStyle(.secondary)
                        .opacity(Double(min(offset / 60, 1)))
                }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 20)
                    .onChanged { v in
                        // Horizontal, rightward only — vertical drags scroll.
                        guard v.translation.width > 0, abs(v.translation.width) > abs(v.translation.height) else { return }
                        offset = min(v.translation.width, 80)
                    }
                    .onEnded { _ in
                        if offset > 60 { onReply() }
                        withAnimation(.spring(duration: 0.25)) { offset = 0 }
                    }
            )
    }
}

/// Accept / Decline on a "message privately" request addressed to us.
struct DmAsk {
    let keyPrefix: String
    let onAccept: () -> Void
    let onDecline: () -> Void
}

/// Every emoji a reaction can carry. The wire form is an index into the
/// shared list, so anything outside it can't be sent — hence a picker,
/// not the system emoji keyboard (Android's reasoning too).
private struct ReactionPicker: View {
    @EnvironmentObject var store: MeshCoreStore
    let onPick: (String) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 12) {
                    ForEach(store.allReactions, id: \.self) { emoji in
                        Button(emoji) { onPick(emoji) }.font(.largeTitle)
                    }
                }
                .padding()
            }
            .navigationTitle("React")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// "Names seen": who has posted here, among the messages kept. Names, not
/// identities — the reason it isn't called "Members" (Android's sheet).
private struct NamesSeenSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let threadKey: String
    let isChannel: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
                    let seen = store.senders(threadKey)
                    if seen.isEmpty {
                        Text("No inbound messages kept here yet.").foregroundStyle(.secondary)
                    }
                    ForEach(seen, id: \.name) { row($0) }
                } footer: {
                    Text(isChannel
                         ? "Anyone with the channel key can post under any name, including one you recognise. A channel message carries a name, not a key, so nothing here can be verified."
                         : "Names as the room relayed them.")
                }
            }
            .navigationTitle("Names seen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private func row(_ s: MessageBook.SenderSeen) -> some View {
        let ago = RelativeTime.shared.agoMillis(elapsedMillis: Int64(Date().timeIntervalSince1970 * 1000) - s.lastSeenAtMs)
        return HStack {
            VStack(alignment: .leading) {
                Text(s.name)
                Text("\(s.messageCount) message(s) · last \(ago)").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if isChannel {
                Button(store.isFiltered(s.name) ? "Show" : "Hide") {
                    store.setFiltered(s.name, !store.isFiltered(s.name))
                }
                .buttonStyle(.borderless)
            }
        }
    }
}
