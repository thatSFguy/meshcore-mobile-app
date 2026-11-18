// Adding, sharing and leaving channels. Validation and the join rules
// are the shared ChannelJoin; these views only collect input and show
// the sentence it returns.

import SwiftUI

/// "+" on the Chats screen: the four ways onto a channel.
struct AddChannelSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss

    enum Mode: String, CaseIterable, Identifiable {
        case hashtag = "Hashtag"
        case newPrivate = "New private"
        case joinPrivate = "Have a key"
        case link = "Paste link"
        var id: String { rawValue }
    }

    @State private var mode: Mode
    @State private var name = ""
    @State private var key = ""
    @State private var link: String

    /// [initialLink] comes from a meshcore:// link another app opened. It
    /// is shown for the person to confirm with Add, never joined silently:
    /// a link from elsewhere is not authority to write to the radio.
    init(initialLink: String? = nil) {
        _mode = State(initialValue: initialLink == nil ? .hashtag : .link)
        _link = State(initialValue: initialLink ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("How", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)

                switch mode {
                case .hashtag:
                    Section {
                        TextField("#channel-name", text: $name)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } footer: {
                        Text("Anyone who knows the name can read a hashtag channel — its key is derived from the name.")
                    }
                case .newPrivate:
                    Section {
                        TextField("Name", text: $name)
                    } footer: {
                        Text("A fresh random key. Share it only with people you trust with every message on the channel. Channel crypto is obfuscation, not security.")
                    }
                case .joinPrivate:
                    Section {
                        TextField("Name", text: $name)
                        TextField("Key (32 hex characters)", text: $key)
                            .font(.system(.body, design: .monospaced))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                case .link:
                    Section {
                        TextField("meshcore://channel?…", text: $link, axis: .vertical)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Button("Paste") { link = UIPasteboard.general.string ?? "" }
                    }
                }

                if let message = store.channelMessage {
                    Section { Text(message) }
                }
            }
            .navigationTitle("Add channel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }.disabled(!store.isReady)
                }
            }
            .onAppear { store.channelMessage = nil }
            .onChange(of: mode) { store.channelMessage = nil }
        }
    }

    private func add() {
        switch mode {
        case .hashtag: store.joinHashtag(name)
        case .newPrivate: store.createPrivateChannel(name)
        case .joinPrivate: store.joinPrivateChannel(name: name, key: key)
        case .link: store.joinSharedLink(link)
        }
    }
}

/// The info sheet for one channel: what it is, sharing, leaving.
struct ChannelInfoSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let index: Int32
    @State private var revealLink = false
    @State private var confirmLeave = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(store.channelSubtitle(index))
                }
                Section {
                    if revealLink, let link = store.shareLink(index) {
                        Text(link)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                        ShareLink(item: link) { Label("Share link", systemImage: "square.and.arrow.up") }
                        Button("Copy link") { UIPasteboard.general.string = link }
                    } else {
                        Button("Show share link") { revealLink = true }
                    }
                } header: {
                    Text("Share")
                } footer: {
                    // ShareUri.encodeChannel: UI that produces this must say
                    // so before it puts the code on screen.
                    Text("Anyone with this link can read every message on the channel — including ones sent before they had it. There is no revoking it short of changing the key everywhere.")
                }
                Section {
                    Button("Leave channel", role: .destructive) { confirmLeave = true }
                        .disabled(!store.isReady)
                }
                if let message = store.channelMessage {
                    Section { Text(message) }
                }
            }
            .navigationTitle(store.title(for: "ch|\(index)"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { store.channelMessage = nil }
            .confirmationDialog("Leave this channel?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Leave", role: .destructive) {
                    store.leaveChannel(index)
                    dismiss()
                }
            } message: {
                Text("The radio frees the slot. Messages already on this phone stay until you delete the thread.")
            }
        }
    }
}
