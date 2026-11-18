// Adding, sharing and leaving channels. Validation and the join rules
// are the shared ChannelJoin; these views only collect input and show
// the sentence it returns.

import SwiftUI
import Shared

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
    @EnvironmentObject var node: NodeSettingsStore
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
                regionSection
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

extension ChannelInfoSheet {
    /// This channel's own region, or the app-wide flood scope (iOS Phase 5,
    /// as Android). Picks from the phone's region list (Settings → Mesh
    /// policies), so only valid names are ever offered.
    @ViewBuilder var regionSection: some View {
        let choices: [String] = node.regions
        let current: String = node.channelRegion(index) ?? ""
        let selection = Binding<String>(get: { current }, set: { node.setChannelRegion(index, $0.isEmpty ? nil : $0) })
        Section {
            Picker("Region", selection: selection) {
                Text("App-wide scope").tag("")
                ForEach(choices, id: \.self) { r in Text("#\(r)").tag(r) }
            }
        } header: {
            Text("Region")
        } footer: {
            Text(choices.isEmpty
                 ? "Add region names under Settings → Mesh policies to scope this channel."
                 : "Posts here flood only through repeaters that carry this region, for that send alone. Routing, not privacy: anyone who hears it can still read it.")
        }
    }
}

/// A meshcore:// link opened from another app: say what it is, then
/// apply it only on a tap. Contact, channel and the refusals all come
/// from the shared LinkImport, including whether a contact's name was
/// signed or merely typed.
struct LinkConfirmSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let link: String
    @State private var preview: LinkImport.Preview?
    @State private var applied = false
    // Settings codes only: whether to set the code's region too, and which.
    @State private var setRegion = false
    @State private var region: String = ""

    var body: some View {
        NavigationStack {
            Form {
                if let preview {
                    Section {
                        Text(preview.title).font(.headline)
                        Text(preview.detail).font(.callout)
                    }
                    if !preview.warnings.isEmpty {
                        Section {
                            ForEach(preview.warnings, id: \.self) { Text($0).foregroundStyle(.red).font(.callout) }
                        }
                    }
                    if preview.isRadio, !preview.regionChoices.isEmpty {
                        Section {
                            if let headline = preview.regionHeadline { Text(headline) }
                            Toggle(preview.regionChoices.count > 1
                                   ? "Set this phone's region to:"
                                   : "Also set this phone's region to #\(preview.regionChoices[0])",
                                   isOn: $setRegion)
                            if setRegion && preview.regionChoices.count > 1 {
                                Picker("Region", selection: $region) {
                                    ForEach(preview.regionChoices, id: \.self) { Text("#\($0)").tag($0) }
                                }
                                .pickerStyle(.inline)
                                .labelsHidden()
                            }
                        } header: {
                            Text("Region")
                        } footer: {
                            Text("Your messages then travel only through repeaters that carry that region. Change it later under Settings → Mesh policies.")
                        }
                    }
                    if applied, let message = store.channelMessage {
                        Section { Text(message) }
                    }
                }
            }
            .navigationTitle("MeshCore link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(applied ? "Done" : "Cancel") { dismiss() }
                }
                if let preview, preview.canApply, !applied {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(preview.isRadio ? "Apply" : "Add") {
                            store.channelMessage = nil
                            if preview.isRadio {
                                store.applyRadioLink(preview, region: setRegion ? region : nil)
                            } else {
                                store.applyLink(preview)
                            }
                            applied = true
                        }
                        .disabled(!store.isReady)
                    }
                }
            }
            .onAppear {
                let p = store.previewLink(link)
                preview = p
                setRegion = p.regionStartsTicked
                region = p.initialRegion ?? ""
            }
        }
    }
}
