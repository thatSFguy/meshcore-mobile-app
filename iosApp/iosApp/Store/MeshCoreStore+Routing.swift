// A contact's routing (iOS Phase 4): the stored route, pinning hops,
// path history, trace and the two-way check. Rules and wording are the
// shared `Routing`, `HopSelection` and `PathHistoryBook`; this file only
// hands them the live contact and the mesh's hop-hash width.

import Foundation
import Shared

extension MeshCoreStore {

    /// The live contact the radio holds, or nil.
    func liveContact(_ keyHex: String) -> Contact? {
        (engine.contacts.value as? [String: Contact])?[keyHex]
    }

    /// Bytes per hop on this mesh — DEVICE_INFO's, never assumed to be 1
    /// when the radio has said otherwise (CLAUDE.md: the recurring defect).
    var meshWidth: Int32 {
        (engine.deviceInfo.value as? DeviceInfo)?.pathHashByteWidth ?? 1
    }

    /// Every contact's name by key, for labelling hops.
    var contactNames: [String: String] {
        let all = (engine.contacts.value as? [String: Contact]) ?? [:]
        return all.mapValues { $0.name.isEmpty ? String($0.publicKeyHex.prefix(12)) : $0.name }
    }

    func relays() -> [Contact] {
        Routing.companion.relays(contacts: Array(((engine.contacts.value as? [String: Contact]) ?? [:]).values))
    }

    func routingMode(_ keyHex: String) -> RoutingMode {
        engine.routingMode(pubKeyHex: keyHex)
    }

    /// The contact's stored route as editable hops, at the route's own width.
    func storedHops(_ keyHex: String) -> [HopSelection.Hop] {
        guard let c = liveContact(keyHex) else { return [] }
        return HopSelection.shared.fromPath(path: c.storedPath, hashWidth: c.pathInfo.hashWidth, contactsByKeyHex: contactNames)
    }

    func applyRouting(_ keyHex: String, mode: RoutingMode, pathHex: String?) async -> String {
        let out = try? await Routing.companion.over(engine: engine).apply(
            keyHex: keyHex, mode: mode, pathHex: pathHex, hashWidth: meshWidth, paths: paths
        )
        let message = out?.message ?? "Radio rejected the route"
        diag.log("Routing", "\(keyHex.prefix(12)) \(mode.name): \(message)")
        if out?.ok == true {
            setPinned(keyHex, mode == RoutingMode.manual)
            scheduleSave()
        }
        return message
    }

    func trace(_ keyHex: String) async -> [String] {
        guard let c = liveContact(keyHex) else { return ["That contact is no longer on the radio."] }
        let lines = (try? await Routing.companion.over(engine: engine).trace(contact: c, meshWidth: meshWidth)) ?? []
        diag.log("Routing", "trace \(keyHex.prefix(12)): \(lines.first ?? "nothing")")
        return lines
    }

    func checkReach(_ keyHex: String) async -> String {
        guard let c = liveContact(keyHex) else { return "That contact is no longer on the radio." }
        let line = (try? await Routing.companion.over(engine: engine).check(contact: c, names: contactNames))
            ?? "No answer. They may not hear you, or the answer was lost on the way back. Try again in a minute."
        diag.log("Routing", "check \(keyHex.prefix(12)): \(line.prefix(40))")
        return line
    }

    /// The newest arrival route from this contact, reversed for a reply.
    func replyRoute(_ keyHex: String) -> String? {
        guard let book = sessionBook else { return nil }
        return Routing.companion.replyRoute(book: book, threadKey: "dm|\(keyHex)")
    }

    func pathRows(_ keyHex: String) -> [PathRow] {
        paths?.forContact(contactKey: keyHex) ?? []
    }

    func deletePath(_ keyHex: String, _ pathHex: String) {
        objectWillChange.send()
        paths?.delete(contactKey: keyHex, pathHex: pathHex)
        scheduleSave()
    }

    func clearPaths(_ keyHex: String) {
        objectWillChange.send()
        paths?.clear(contactKey: keyHex)
        scheduleSave()
    }

    // Routes the person pinned, so later recovery never undoes a choice.
    // Android keeps the same set; iOS keeps it for parity until admin
    // path recovery is ported.

    private var pinnedKey: String { "pinnedRoutes" }

    func isPinned(_ keyHex: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: pinnedKey) ?? []).contains(keyHex.lowercased())
    }

    func setPinned(_ keyHex: String, _ pinned: Bool) {
        var set = Set(UserDefaults.standard.stringArray(forKey: pinnedKey) ?? [])
        if pinned { set.insert(keyHex.lowercased()) } else { set.remove(keyHex.lowercased()) }
        UserDefaults.standard.set(Array(set).sorted(), forKey: pinnedKey)
    }
}

extension MeshCoreStore {
    /// GPX of the radio's contacts with a position, or nil when none has one.
    func gpxText() -> String? {
        Gpx.shared.fromContacts(contacts: Array(((engine.contacts.value as? [String: Contact]) ?? [:]).values))
    }
}
