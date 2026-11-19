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
            deviceCountry: Locale.current.region?.identifier,
            unitsPreference: unitsPreference
        )
    }

    /// One status poll's noise floor, for the noise-floor watch.
    func noiseFloor(_ keyHex: String) async -> Int? {
        let s = try? await repeaterAdmin.statusRecovered(keyHex: keyHex, signedIn: adminSession(keyHex).signedIn, pinned: isPinned(keyHex))
        return (s ?? nil).map { Int($0.noiseFloor) }
    }
}

extension MeshCoreStore {
    /// The nodes a sweep at [days] would remove (shared StaleNodes rules).
    func staleSweep(days: Int) -> StaleNodes.Sweep {
        StaleSweep.companion.sweep(nodes: nodes, olderThanDays: Int32(days),
                                   nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
    }

    /// Remove them from the RADIO, one at a time, oldest first; the
    /// sentence to show. Their neighbour readings go too, as on Android.
    func removeStaleNodes(days: Int) async -> String {
        guard isReady else { return "Not connected to a radio." }
        let result = try? await StaleSweep.companion.over(engine: engine).run(sweep: staleSweep(days: days))
        for key in result?.removedKeys ?? [] { neighbourBook.clear(repeaterKeyHex: key) }
        objectWillChange.send()
        let message = result?.message ?? "Nothing was removed."
        diag.log("Store", "stale sweep (\(days) d): \(message)")
        return message
    }
}

// MARK: - Discovered nodes (iOS after 0.1.0)

extension MeshCoreStore {
    var heardNodes: [HeardNode] { (heard?.nodes.value as? [HeardNode]) ?? [] }

    func heardDetail(_ n: HeardNode) -> String {
        DiscoveredBook.companion.detail(node: n, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    func addHeard(_ n: HeardNode) async -> String {
        let msg = (try? await DiscoveredBook.companion.add(engine: engine, node: n)) ?? "Couldn't add it."
        diag.log("Store", "add heard \(n.keyHex.prefix(12)): \(msg)")
        objectWillChange.send()
        return msg
    }

    func dismissHeard(_ n: HeardNode) {
        heard?.dismiss(keyHex: n.keyHex)
        objectWillChange.send()
        scheduleSave()
    }

    func clearHeard() {
        heard?.clear()
        objectWillChange.send()
        scheduleSave()
    }

    func discoverNodes() async -> String {
        let msg = (try? await DiscoveredBook.companion.discover(engine: engine)) ?? "No repeater answered."
        diag.log("Store", "discover: \(msg)")
        return msg
    }
}

// MARK: - Purge local data (shared Purge wording, as Android)

extension MeshCoreStore {
    /// Delete this phone's copy of everything; the radio is not touched.
    /// Returns the facts, as Android reports them.
    func purgeLocal(alsoSecrets: Bool) -> String {
        // History is per radio and opened when one connects (as Android).
        guard let messages = clearAllHistory() else { return "Not connected — nothing to purge against." }
        paths?.clearAll()
        heard?.clear()
        drafts = [:]
        consoleLines = [:]
        let defaults = UserDefaults.standard
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("nick_") {
            defaults.removeObject(forKey: key)
        }
        defaults.removeObject(forKey: "pinnedRoutes")
        defaults.removeObject(forKey: "mutedThreads")
        defaults.removeObject(forKey: "pinnedThreads")
        node.forgetRegionLabels()
        if alsoSecrets { CommunityKeychain.forgetAll() }
        objectWillChange.send()
        saveNow() // writes the emptied history, paths and inbox
        diag.log("Store", "purged local data (\(messages) messages, secrets: \(alsoSecrets))")
        // iOS keeps no copy of the radio's contacts or channels (they are
        // read live), so there are none to count.
        return Purge.shared.result(messages: messages, contacts: nil, channels: nil, alsoSecrets: alsoSecrets)
    }
}
