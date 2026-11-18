// The connected radio's own settings, for the "This node" spokes
// (iOS Phase 3). Reads come from the engine's flows; every write goes
// through `run`, which reports the radio's answer as a sentence, like
// Android's deviceAction. The rules — ranges, bit layouts, wording —
// are shared (NodeSettings, OtherParams, RadioRetune, RegionDiscovery);
// this class only moves values between them and SwiftUI.

import Foundation
import SwiftUI
import Shared

@MainActor
final class NodeSettingsStore: ObservableObject {

    @Published private(set) var selfInfo: SelfInfo?
    @Published private(set) var deviceInfo: DeviceInfo?
    @Published private(set) var autoAddFlags: Int?
    @Published private(set) var customVars: [String: String] = [:]
    @Published private(set) var floodScope: String?
    @Published private(set) var radioDefaultScope: RadioDefaultScope?
    @Published private(set) var floodScopeStuck: String?
    /// The last write's outcome, shown at the bottom of a spoke.
    @Published var message: String?
    @Published private(set) var busy = false

    /// The app's own region list (phone-side; nothing on the radio).
    @AppStorage("regions") private var regionsRaw: String = ""
    /// The app-wide flood scope, persisted so it can be restored on
    /// connect: "" = cleared, absent = never set (Android's rule).
    private static let floodScopeKey = "floodScope"

    private let engine: MeshCoreEngine
    private let scope: Kotlinx_coroutines_coreCoroutineScope
    private var watches: [FlowWatch] = []
    private let diag = DiagnosticsLog.shared

    init(engine: MeshCoreEngine, scope: Kotlinx_coroutines_coreCoroutineScope) {
        self.engine = engine
        self.scope = scope
        engine.restoreFloodScope(region: UserDefaults.standard.string(forKey: Self.floodScopeKey))
        watch(engine.selfInfo) { [weak self] in self?.selfInfo = $0 as? SelfInfo }
        watch(engine.deviceInfo) { [weak self] in self?.deviceInfo = $0 as? DeviceInfo }
        watch(engine.autoAddFlags) { [weak self] in self?.autoAddFlags = ($0 as? KotlinInt)?.intValue }
        watch(engine.customVars) { [weak self] in self?.customVars = ($0 as? [String: String]) ?? [:] }
        watch(engine.radioDefaultScope) { [weak self] in self?.radioDefaultScope = $0 as? RadioDefaultScope }
        watch(engine.floodScopeStuck) { [weak self] in self?.floodScopeStuck = $0 as? String }
        watch(engine.floodScopeRegion) { [weak self] value in
            let region = value as? String
            self?.floodScope = region
            // Mirror what the engine holds, so the next launch restores it.
            if let region { UserDefaults.standard.set(region, forKey: Self.floodScopeKey) }
        }
    }

    private func watch(_ flow: Kotlinx_coroutines_coreFlow, _ onEach: @escaping (Any?) -> Void) {
        watches.append(FlowWatchKt.watch(flow: flow, scope: scope) { onEach($0) })
    }

    // MARK: - Writes

    /// Run one write; `ok`/`refused` are the sentences for its answer.
    private func run(_ ok: String, refused: String = "Radio rejected the change",
                     _ write: @escaping () async throws -> Bool) {
        busy = true
        Task {
            let done = (try? await write()) ?? false
            self.message = done ? ok : refused
            self.diag.log("Node", "\(ok): \(done ? "ok" : "refused")")
            self.busy = false
        }
    }

    private func bool(_ b: KotlinBoolean?) -> Bool { b?.boolValue ?? false }

    func refreshSelf() {
        Task { _ = try? await engine.refreshSelfInfo() }
    }

    func refreshDevice() {
        Task { _ = try? await engine.refreshDeviceInfo() }
    }

    // Identity

    func setName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        run("Name updated") { self.bool(try await self.engine.setAdvertName(name: trimmed)) }
    }

    func setPosition(lat: String, lon: String) {
        guard let p = NodeSettings.shared.parsePosition(lat: lat, lon: lon) else {
            message = "Latitude is −90 to 90 and longitude −180 to 180, in decimal degrees."
            return
        }
        run("Location updated") {
            self.bool(try await self.engine.setAdvertLatLon(lat: p.latitude, lon: p.longitude))
        }
    }

    func sendAdvert(flood: Bool) {
        run(flood ? "Flood advert sent" : "Zero-hop advert sent", refused: "The radio did not send the advert") {
            self.bool(try await self.engine.sendSelfAdvert(flood: flood))
        }
    }

    // Radio

    func setRadioParams(freq: String, bw: String, sf: String, cr: String) {
        guard let p = NodeSettings.shared.parseRadioParams(freqMhz: freq, bwKhz: bw, sf: sf, cr: cr) else {
            message = NodeSettings.shared.RADIO_PARAMS_HELP
            return
        }
        run("Radio params updated") {
            self.bool(try await self.engine.setRadioParams(freqKhz: p.freqKhz, bwHz: p.bwHz, sf: p.sf, cr: p.cr))
        }
    }

    func setTxPower(_ text: String) {
        guard let info = selfInfo,
              let dbm = NodeSettings.shared.parseTxPower(text: text, maxDbm: info.maxTxPowerDbm) else {
            message = "TX power is a whole number of dBm, 1 to \(selfInfo?.maxTxPowerDbm ?? 0)."
            return
        }
        run("TX power updated") { self.bool(try await self.engine.setTxPower(dbm: dbm.int32Value)) }
    }

    func applyPreset(_ preset: RadioPresets.Preset) {
        busy = true
        Task {
            let out = try? await RadioRetune.companion.over(engine: engine).applyPreset(preset: preset)
            self.message = out?.message ?? "Radio rejected the \(preset.name) parameters"
            self.diag.log("Node", "preset \(preset.name): \(out?.message ?? "failed")")
            self.busy = false
        }
    }

    /// The meshcore:// settings link for what the radio runs now, or nil
    /// when the radio has not reported its hop-hash width.
    var shareLink: String? {
        guard let i = selfInfo else { return nil }
        let width = deviceInfo.map { KotlinInt(int: $0.pathHashByteWidth) }
        return RadioShare.shared.uriFor(
            frequencyKhz: i.freqKhz, bandwidthHz: i.bwHz,
            spreadingFactor: i.sf, codingRate: i.cr,
            pathHashByteWidth: width,
            region: floodScope,
            meshName: NodeSettings.shared.presetNameFor(freqKhz: i.freqKhz, bwHz: i.bwHz, sf: i.sf, cr: i.cr)
        )
    }

    func radioStats() async -> MeshCoreEngine.Stats? {
        (try? await engine.getRadioStats()) ?? nil
    }

    func reboot() {
        Task {
            _ = try? await engine.reboot()
            self.message = "Reboot sent"
            self.diag.log("Node", "reboot sent")
        }
    }

    // PIN

    func setPin(_ text: String) {
        guard let pin = DevicePin.shared.parse(text: text) else {
            message = "A PIN is exactly 6 digits"
            return
        }
        run("PIN changed. Restart the radio, then forget it in iOS Bluetooth settings and pair again.",
            refused: "The radio did not accept the new PIN") {
            self.bool(try await self.engine.setDevicePin(pin: pin.int32Value))
        }
    }

    // Clock

    /// Radio clock minus phone clock, seconds; nil when the radio did not answer.
    func readClock() async -> (radio: Date, drift: Int64)? {
        guard let t = (try? await engine.deviceTime()) ?? nil else { return nil }
        let radio = t.int64Value
        return (Date(timeIntervalSince1970: TimeInterval(radio)), radio - Int64(Date().timeIntervalSince1970))
    }

    func syncClock() async {
        let ok = bool(try? await engine.syncDeviceClock())
        message = ok ? "Radio clock synced" : "Radio rejected the change"
    }

    // Policies

    var otherParams: OtherParams? { selfInfo.map { OtherParams.companion.of(info: $0) } }

    func setOtherParams(_ p: OtherParams) {
        run("Policies updated") {
            self.bool(try await self.engine.setOtherParams(
                allowTelemetryFlags: p.telemetryFlags,
                advertLocationPolicy: p.advertLocationPolicy,
                multiAcks: p.multiAcks
            ))
        }
    }

    func setPathHashMode(_ mode: Int) {
        run("Path hash mode set to \(mode) (\(mode + 1) B/hop)") {
            self.bool(try await self.engine.setPathHashMode(mode: Int32(mode)))
        }
    }

    func setFloodScope(_ region: String?) {
        if let region, !region.isEmpty, !Regions.shared.isValid(raw: region) {
            message = RegionList.shared.INVALID_NAME
            return
        }
        let target = (region?.isEmpty ?? true) ? nil : region
        if target == nil { UserDefaults.standard.set("", forKey: Self.floodScopeKey) }
        let ok = target.map { "Flood scope set to #\(Regions.shared.canonical(raw: $0) ?? $0)" } ?? "Flood scope cleared"
        run(ok) { self.bool(try await self.engine.setFloodScope(region: target)) }
    }

    var radioDefaultScopeLine: String? {
        radioDefaultScope.flatMap { NodeSettings.shared.defaultScopeLine(scope: $0) }
    }

    var regions: [String] { regionsRaw.split(separator: "\n").map(String.init) }

    func addRegion(_ name: String) {
        guard let next = RegionList.shared.add(list: regions, name: name) else {
            message = RegionList.shared.INVALID_NAME
            return
        }
        objectWillChange.send()
        regionsRaw = next.joined(separator: "\n")
        message = "Added #\(Regions.shared.canonical(raw: name) ?? name)"
    }

    /// Add several names at once (a restored backup); invalid ones are skipped.
    func mergeRegions(_ names: [String]) {
        var list = regions
        for n in names {
            if let next = RegionList.shared.add(list: list, name: n) { list = next }
        }
        objectWillChange.send()
        regionsRaw = list.joined(separator: "\n")
    }

    func removeRegion(_ name: String) {
        objectWillChange.send()
        regionsRaw = RegionList.shared.remove(list: regions, name: name).joined(separator: "\n")
        message = "Removed #\(name)"
    }

    func discoverRegions() async -> RegionDiscovery.Result? {
        let r = try? await RegionDiscovery.companion.over(engine: engine).run()
        diag.log("Node", "region discovery: \(r.map { "\($0.answered)/\($0.asked) answered, \($0.names.count) names" } ?? "failed")")
        return r
    }

    // Auto-add, custom variables

    func refreshAutoAdd() { Task { _ = try? await engine.requestAutoAddConfig() } }

    func setAutoAddBit(_ bit: Int32, on: Bool) {
        guard let current = autoAddFlags else { return }
        let next = NodeSettings.shared.toggleFlag(flags: Int32(current), bit: bit, on: on)
        run("Auto-add policy updated") { self.bool(try await self.engine.setAutoAddConfig(flags: next)) }
    }

    func refreshCustomVars() { Task { _ = try? await engine.requestCustomVars() } }

    func setCustomVar(key: String, value: String) {
        guard let kv = NodeSettings.shared.customVarAssignment(key: key, value: value) else {
            message = "A variable name can't be blank or contain ':'."
            return
        }
        let name = key.trimmingCharacters(in: .whitespaces)
        run("\(name) updated") { self.bool(try await self.engine.setCustomVar(keyValue: kv)) }
    }
}
