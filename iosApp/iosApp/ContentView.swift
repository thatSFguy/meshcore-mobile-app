// Tab shell: Chats / Nodes / Map / Settings — mirrors the Android
// bottom navigation.

import SwiftUI

struct ContentView: View {
    @EnvironmentObject var store: MeshCoreStore

    var body: some View {
        TabView {
            ChatsView()
                .tabItem { Label("Chats", systemImage: "message") }
                // The one number visible from every tab (PARITY: unread
                // counts on the tabs). Zero shows no badge.
                .badge(store.unreadTotal)
            // Simple mode keeps Chats and Settings (shared AppModes.tabs).
            if !store.isSimple {
                NodesView()
                    .tabItem { Label("Nodes", systemImage: "person.2") }
                NodeMapView()
                    .tabItem { Label("Map", systemImage: "map") }
            }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
        .onOpenURL { store.openIncoming($0) }
        // Settings → App → Theme ("System" follows iOS).
        .preferredColorScheme(store.colorScheme)
        // First-run setup until it's finished or skipped (Android's setupComplete).
        .fullScreenCover(isPresented: Binding(get: { !store.setupComplete }, set: { if !$0 { store.setupComplete = true } })) {
            SetupView()
                .environmentObject(store)
                .environmentObject(store.node)
        }
        // A community code (scanned or pasted): confirm before joining.
        .alert("Join community \(store.pendingCommunity.flatMap { store.communityName($0) } ?? "")?",
               isPresented: Binding(get: { store.pendingCommunity != nil }, set: { if !$0 { store.pendingCommunity = nil } })) {
            Button("Join") {
                if let code = store.pendingCommunity { store.joinCommunity(code) }
                store.pendingCommunity = nil
            }
            .disabled(!store.isReady)
            Button("Cancel", role: .cancel) { store.pendingCommunity = nil }
        } message: {
            Text(store.pendingCommunity.flatMap { store.communityName($0) } == nil
                 ? "This community code can't be read."
                 : "This adds the community's public channel. Its secret stays in this iPhone's Keychain, so the community's other channels can be joined later. Channels are obfuscated, not secure.")
        }
        .sheet(isPresented: Binding(
            get: { store.pendingLink != nil },
            set: { if !$0 { store.pendingLink = nil } }
        )) {
            if let link = store.pendingLink { LinkConfirmSheet(link: link) }
        }
    }
}
