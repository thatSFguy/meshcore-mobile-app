// Tab shell: Chats / Nodes / Map / Settings — mirrors the Android
// bottom navigation.

import SwiftUI

struct ContentView: View {
    @EnvironmentObject var store: MeshCoreStore

    var body: some View {
        TabView {
            ChatsView()
                .tabItem { Label("Chats", systemImage: "message") }
            NodesView()
                .tabItem { Label("Nodes", systemImage: "person.2") }
            NodeMapView()
                .tabItem { Label("Map", systemImage: "map") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
    }
}
