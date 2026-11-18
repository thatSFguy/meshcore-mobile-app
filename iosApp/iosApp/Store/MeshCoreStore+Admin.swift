// Repeater admin beyond sign-in/status/console (iOS Phase 5): the guest
// card's owner and regions, the access list, neighbours, one-shot
// commands. Every call goes through the shared RepeaterAdmin, which
// restates the engine's defaults and applies Android's path-recovery
// gate (iOS keeps no password, so re-authentication never runs).

import Foundation
import Shared

extension MeshCoreStore {

    private func signedIn(_ keyHex: String) -> Bool { adminSession(keyHex).signedIn }

    func ownerLine(_ keyHex: String) async -> String {
        (try? await repeaterAdmin.ownerLine(keyHex: keyHex, signedIn: signedIn(keyHex), pinned: isPinned(keyHex)))
            ?? RepeaterAdmin.companion.OWNER_SILENT
    }

    func floodRegionsLine(_ keyHex: String) async -> String {
        (try? await repeaterAdmin.floodRegionsLine(keyHex: keyHex)) ?? RepeaterAdmin.companion.REGIONS_SILENT
    }

    /// The access list, or nil with the reason in `adminNote`.
    func accessList(_ keyHex: String) async -> [AccessList.BinEntry]? {
        let list = try? await repeaterAdmin.accessList(keyHex: keyHex, signedIn: signedIn(keyHex), pinned: isPinned(keyHex))
        return list ?? nil
    }

    /// Fetch the neighbour table into the book; the sentence to show. A
    /// node not signed in to is tried with a blank password first — it
    /// works on any node that already knows this radio.
    func collectNeighbours(_ keyHex: String) async -> String {
        if !signedIn(keyHex) {
            if let refused = await signIn(keyHex, password: "") { return refused }
        }
        let message = (try? await repeaterAdmin.collectNeighbours(
            keyHex: keyHex, signedIn: signedIn(keyHex), pinned: isPinned(keyHex),
            book: neighbourBook, nowMs: Int64(Date().timeIntervalSince1970 * 1000)
        )) ?? "No reply from the node — is it in range?"
        objectWillChange.send()
        diag.log("Admin", "neighbours \(keyHex.prefix(12)): \(message)")
        return message
    }

    func neighbourLinks(_ keyHex: String) -> [NeighbourLink] {
        neighbourBook.links(
            repeaterKeyHex: keyHex,
            contacts: Array(((engine.contacts.value as? [String: Contact]) ?? [:]).values),
            nowMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    func forgetNeighbours(_ keyHex: String) {
        objectWillChange.send()
        neighbourBook.clear(repeaterKeyHex: keyHex)
    }

    /// Ask a repeater to re-discover its neighbours, wait for the answers
    /// to come back over the air, then read the table again.
    func probeNeighbours(_ keyHex: String) async -> String {
        let sent = (try? await repeaterAdmin.sendOnly(keyHex: keyHex, command: "discover.neighbors"))?.boolValue ?? false
        guard sent else { return "The node did not take the probe." }
        try? await Task.sleep(nanoseconds: UInt64(NeighbourBook.companion.PROBE_SETTLE_MS) * 1_000_000)
        return await collectNeighbours(keyHex)
    }

    /// A command whose reply isn't awaited (reboot, clock sync).
    func sendOnly(_ keyHex: String, _ command: String) async -> Bool {
        let ok = (try? await repeaterAdmin.sendOnly(keyHex: keyHex, command: command))?.boolValue ?? false
        diag.log("Admin", "\(RepeaterAdmin.companion.displayed(line: command)) → \(ok ? "sent" : "not sent")")
        return ok
    }

    func syncNodeClock(_ keyHex: String) async -> Bool {
        await sendOnly(keyHex, RepeaterAdmin.companion.clockSyncCommand(nowSeconds: Int64(Date().timeIntervalSince1970)))
    }
}

extension MeshCoreStore {
    func telemetry(_ keyHex: String, type: Int32) async -> RepeaterAdmin.TelemetryView? {
        try? await repeaterAdmin.telemetry(
            keyHex: keyHex, contactType: type,
            signedIn: adminSession(keyHex).signedIn, pinned: isPinned(keyHex),
            deviceCountry: Locale.current.region?.identifier
        )
    }

    /// One status poll's noise floor, for the noise-floor watch.
    func noiseFloor(_ keyHex: String) async -> Int? {
        let s = try? await repeaterAdmin.statusRecovered(keyHex: keyHex, signedIn: adminSession(keyHex).signedIn, pinned: isPinned(keyHex))
        return (s ?? nil).map { Int($0.noiseFloor) }
    }
}
