// Diagnostics: the iOS counterpart of Android's DiagnosticsLog.
//
// OFF by default (SCOPE.md: "one redaction-aware, off-by-default
// diagnostics log"); Settings → Diagnostics turns it on. While on, every
// line is redacted by the shared rule (LogRedaction — the same one
// Android uses), kept in memory (last 500, never written to disk), and
// mirrored to the unified log under subsystem
// io.github.thatsfguy.meshcore, so a tethered computer can read it
// (`idevicesyslog -p iosApp`). Android mirrors to logcat only in debug
// builds; iOS has no debug build off CI, and the unified log is not
// readable by other apps, so the mirror follows the same toggle instead
// (IOS_DECISIONS.md §36).
//
// Callable from any thread: the engine, transports and supervisor log
// from wherever their coroutine runs.

import Foundation
import os
import Shared

final class DiagnosticsLog: ObservableObject, @unchecked Sendable {
    static let shared = DiagnosticsLog()

    static let enabledKey = "diagnosticsEnabled"
    private static let maxLines = 500
    private static let logger = Logger(subsystem: "io.github.thatsfguy.meshcore", category: "diagnostics")

    /// Main-thread only (it drives SwiftUI).
    @Published private(set) var lines: [String] = []

    var isEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    func log(_ tag: String, _ message: String) {
        guard isEnabled else { return }
        let clean = LogRedaction.shared.redact(message: message)
        // Already redacted, so public: a private field shows as <private>
        // to the very computer this mirror exists for.
        Self.logger.notice("[\(tag, privacy: .public)] \(clean, privacy: .public)")
        let line = "\(Self.stamp()) [\(tag)] \(clean)"
        DispatchQueue.main.async {
            self.lines.append(line)
            if self.lines.count > Self.maxLines {
                self.lines.removeFirst(self.lines.count - Self.maxLines)
            }
        }
    }

    /// A closure for a Kotlin `(String) -> Unit` log parameter.
    func sink(_ tag: String) -> (String) -> Void {
        { [weak self] message in self?.log(tag, message) }
    }

    func clear() {
        DispatchQueue.main.async { self.lines.removeAll() }
    }

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private static func stamp() -> String {
        // DateFormatter is thread-safe for formatting on iOS 7+.
        formatter.string(from: Date())
    }
}
