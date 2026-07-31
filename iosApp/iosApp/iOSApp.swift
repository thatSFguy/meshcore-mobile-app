// SPDX-License-Identifier: MIT
//
// iOS app entry point. Owns the lone MeshCoreStore for the app's
// lifetime and injects it into the SwiftUI environment (same pattern
// as the sibling reticulum-mobile-app).

import SwiftUI

@main
struct MeshCoreApp: App {
    @StateObject private var store = MeshCoreStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
    }
}
