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
            NodesView()
                .tabItem { Label("Nodes", systemImage: "person.2") }
            NodeMapView()
                .tabItem { Label("Map", systemImage: "map") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
        .onOpenURL { store.openIncoming($0) }
        .sheet(isPresented: Binding(
            get: { store.pendingLink != nil },
            set: { if !$0 { store.pendingLink = nil } }
        )) {
            AddChannelSheet(initialLink: store.pendingLink)
        }
    }
}
