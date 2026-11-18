// SPDX-License-Identifier: MIT
//
// iOS app entry point. Owns the lone MeshCoreStore for the app's
// lifetime and injects it into the SwiftUI environment (same pattern
// as the sibling reticulum-mobile-app).

import SwiftUI
import UserNotifications

/// Shows message banners while the app is open too — on another tab, or
/// another thread. The thread on screen never raises one: the shared
/// MessagingSession does not report a message for the active thread.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }
}

@main
struct MeshCoreApp: App {
    @StateObject private var store = MeshCoreStore()
    @Environment(\.scenePhase) private var scenePhase
    private let presenter = NotificationPresenter()

    init() {
        UNUserNotificationCenter.current().delegate = presenter
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
        .onChange(of: scenePhase) { _, phase in
            // Don't lose the last half-second of history to a debounce
            // when iOS suspends us.
            if phase != .active { store.saveNow() }
        }
    }
}
