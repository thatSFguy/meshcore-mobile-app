// Settings → This node (iOS Phase 3): the connected radio's own
// settings. Wording follows Android's spokes; ranges, bit layouts and
// multi-step writes are shared (NodeSettings, OtherParams, RadioRetune,
// RegionDiscovery), so this file only lays them out.

import SwiftUI
import UniformTypeIdentifiers
import MapKit
import Shared

/// Shown on a node spoke when there is no radio to read.
private struct NeedsRadio: View {
    let text: String
    var body: some View { Text(text).foregroundStyle(.secondary) }
}

/// The last write's outcome, at the foot of a spoke.
private struct OutcomeSection: View {
    @EnvironmentObject var node: NodeSettingsStore
    var body: some View {
        if let message = node.message {
            Section { Text(message).font(.callout) }
        }
    }
}

// MARK: - Bluetooth PIN

struct PinSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var pin = ""
    @State private var confirm = false
    @State private var offerReboot = false

    /// Built in steps: one long `+` chain is more than the Swift type
    /// checker will solve in time (CI, 2026-11-18).
    private var confirmText: String {
        let opening: String = DevicePin.shared.isUseDefault(text: pin)
            ? "The radio will drop its stored PIN and go back to its built-in default."
            : "The radio will use \(pin) from now on."
        let points: [String] = [
            "• It keeps pairing with the OLD pin until the node is restarted.",
            "• This iPhone stays paired on the old PIN until you remove the pairing: Settings › Bluetooth › (i) next to the radio › Forget This Device. Removing it here in the app is not the same.",
            "• Every other phone paired with this radio has to do the same.",
            "• Nothing can read the PIN back off the radio. If you forget it, Bluetooth access is gone until you reach the node another way.",
        ]
        return opening + "\n\n" + points.joined(separator: "\n") + "\n\nWrite it down before you continue."
    }

    private var hint: String? {
        if let r = DevicePin.shared.rejection(text: pin) { return r }
        if DevicePin.shared.isUseDefault(text: pin) { return "Clears the PIN — the node reverts to its built-in default" }
        if DevicePin.shared.isFactoryDefault(text: pin) { return "That is the factory default — it protects nothing" }
        return nil
    }

    var body: some View {
        Form {
            if !store.isReady {
                NeedsRadio(text: "Connect to a radio to change its pairing PIN.")
            } else {
                Section {
                    LabeledContent("Configured", value: DevicePin.shared.describe(pin: node.deviceInfo?.blePin))
                    SecureField("New PIN (6 digits)", text: $pin)
                        .keyboardType(.numberPad)
                        .onChange(of: pin) { _, typed in
                            let digits = String(typed.filter(\.isNumber).prefix(Int(DevicePin.shared.LENGTH)))
                            if digits != typed { pin = digits }
                        }
                    if let hint {
                        Text(hint).font(.caption)
                            .foregroundStyle(DevicePin.shared.rejection(text: pin) != nil ? .red : .secondary)
                    }
                    Button("Change PIN") { confirm = true }
                        .disabled(!DevicePin.shared.isAcceptable(text: pin) || node.busy)
                } header: {
                    Text("Bluetooth pairing PIN")
                } footer: {
                    Text("Nodes without a screen ship with \(DevicePin.shared.FACTORY_DEFAULT), which is public: until it is changed, anyone in Bluetooth range can pair with this radio and read its contacts, messages and settings.")
                }
                OutcomeSection()
            }
        }
        .navigationTitle("Bluetooth PIN")
        .alert("Change the pairing PIN?", isPresented: $confirm) {
            Button("Change it", role: .destructive) {
                node.setPin(pin)
                pin = ""
                offerReboot = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(confirmText)
        }
        .alert("Reboot to apply it?", isPresented: $offerReboot) {
            Button("Reboot now") { node.reboot() }
            Button("Later", role: .cancel) {}
        } message: {
            Text("The new PIN is stored, but the radio goes on pairing with the old one until it restarts. After the reboot, forget the radio in iOS Bluetooth settings; the next connection will ask for the new PIN.")
        }
    }
}

// MARK: - Identity

struct IdentitySpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var name = ""
    @State private var lat = ""
    @State private var lon = ""
    @State private var showQr = false
    @State private var picking = false

    var body: some View {
        Form {
            if let info = node.selfInfo, store.isReady {
                Section {
                    Text(info.publicKeyHex)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                } header: { Text("Public key") }

                Section {
                    TextField("Advertised name", text: $name)
                        .autocorrectionDisabled()
                    Button("Set name") { node.setName(name) }
                        .disabled(!nameChanged(from: info.name) || node.busy)
                } header: { Text("Name") }

                Section {
                    TextField("Latitude", text: $lat).keyboardType(.numbersAndPunctuation)
                    TextField("Longitude", text: $lon).keyboardType(.numbersAndPunctuation)
                    // Fills the fields, as Android's picker does; Set sends it.
                    Button("Pick on map…") { picking = true }
                    Button("Set position") { node.setPosition(lat: lat, lon: lon) }
                        .disabled(node.busy)
                } header: {
                    Text("Position")
                } footer: {
                    if !GeoKt.isPlausiblePosition(lat: KotlinDouble(double: info.latitude), lon: KotlinDouble(double: info.longitude)) {
                        Text("This radio has no position set. It is still on the mesh — adverts just go out without a location.")
                    } else {
                        Text("This position is broadcast to the whole mesh in your adverts — anyone in radio range, and anyone they relay to, can see it.")
                    }
                }

                Section {
                    Button("Advert to neighbours (zero hop)") { node.sendAdvert(flood: false) }
                    Button("Advert mesh-wide (flood)") { node.sendAdvert(flood: true) }
                    Button("Show my contact QR") { showQr = true }
                } header: {
                    Text("Announce")
                } footer: {
                    Text("A flood advert reaches nodes beyond direct range, and costs everyone airtime.")
                }
                OutcomeSection()
            } else {
                NeedsRadio(text: "Connect to a radio to edit node identity.")
            }
        }
        .navigationTitle("Identity")
        .sheet(isPresented: $picking) {
            PositionPicker(lat: Double(lat), lon: Double(lon)) { la, lo in
                lat = String(format: "%.5f", la)
                lon = String(format: "%.5f", lo)
            }
        }
        .onAppear { node.refreshSelf(); load() }
        .onChange(of: node.selfInfo) { _, _ in load() }
        .sheet(isPresented: $showQr) { MyContactSheet() }
    }

    private func nameChanged(from current: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        return !trimmed.isEmpty && trimmed != current
    }

    private func load() {
        guard let info = node.selfInfo else { return }
        name = info.name
        let plausible = GeoKt.isPlausiblePosition(lat: KotlinDouble(double: info.latitude), lon: KotlinDouble(double: info.longitude))
        lat = plausible ? String(format: "%.5f", info.latitude) : ""
        lon = plausible ? String(format: "%.5f", info.longitude) : ""
    }
}

// MARK: - Radio

struct RadioSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var freq = ""
    @State private var bw = ""
    @State private var sf = ""
    @State private var cr = ""
    @State private var tx = ""
    @State private var showPresets = false
    @State private var showShare = false
    @State private var scanning = false

    var body: some View {
        Form {
            if let info = node.selfInfo, store.isReady {
                Section {
                    Text(NodeSettings.shared.presetMatchLine(freqKhz: info.freqKhz, bwHz: info.bwHz, sf: info.sf, cr: info.cr))
                    Button("Use a regional preset…") { showPresets = true }
                    Button("Scan settings QR…") { scanning = true }
                    Button("Share these settings…") { showShare = true }
                } footer: {
                    Text("Parameters must match your mesh or the node goes deaf.")
                }

                Section {
                    LabeledContent("Freq (MHz)") { TextField("910.525", text: $freq).multilineTextAlignment(.trailing) }
                    LabeledContent("BW (kHz)") { TextField("62.5", text: $bw).multilineTextAlignment(.trailing) }
                    LabeledContent("SF") { TextField("7", text: $sf).multilineTextAlignment(.trailing) }
                    LabeledContent("CR") { TextField("5", text: $cr).multilineTextAlignment(.trailing) }
                    Button("Apply radio params") { node.setRadioParams(freq: freq, bw: bw, sf: sf, cr: cr) }
                        .disabled(node.busy)
                } header: {
                    Text("Radio parameters")
                } footer: {
                    Text("Every node you talk to must be on these same four values. If they aren't, you won't see errors — you'll see an empty mesh.")
                }
                .keyboardType(.decimalPad)

                Section {
                    LabeledContent("TX power (dBm, max \(info.maxTxPowerDbm))") {
                        TextField("20", text: $tx).multilineTextAlignment(.trailing).keyboardType(.numberPad)
                    }
                    Button("Set TX power") { node.setTxPower(tx) }
                        .disabled(node.busy)
                } footer: {
                    Text(RadioPresets.shared.REGULATORY_CAVEAT)
                }

                SignalSection()
                OutcomeSection()
            } else {
                NeedsRadio(text: "Connect to a radio to edit radio parameters.")
            }
        }
        .navigationTitle("Radio")
        .onAppear { node.refreshSelf(); node.refreshDevice(); load() }
        .onChange(of: node.selfInfo) { _, _ in load() }
        .sheet(isPresented: $showPresets) { PresetSheet() }
        .sheet(isPresented: $showShare) { RadioShareSheet() }
        .sheet(isPresented: $scanning) {
            QrScannerSheet { text in store.openScanned(text) }
        }
    }

    private func load() {
        guard let info = node.selfInfo else { return }
        freq = RadioUnits.shared.khzToMhzText(khz: info.freqKhz)
        bw = RadioUnits.shared.hzToKhzText(hz: info.bwHz)
        sf = "\(info.sf)"
        cr = "\(info.cr)"
        tx = "\(info.txPowerDbm)"
    }
}

/// Live noise floor and last-packet readings, polled every two seconds
/// while on screen (shared SignalPoll decides when to stop).
private struct SignalSection: View {
    @EnvironmentObject var node: NodeSettingsStore
    // SignalPoll is a Kotlin class SwiftUI cannot observe, so what it
    // says is copied into state after each reading.
    @State private var poll = SignalPoll()
    @State private var status: String? = "Reading…"
    @State private var rows: [(label: String, value: String)] = []
    @State private var rangeHint: String?

    var body: some View {
        Section {
            if let status {
                Text(status).foregroundStyle(.secondary)
            }
            ForEach(rows.indices, id: \.self) { i in
                LabeledContent(rows[i].label, value: rows[i].value)
            }
            if let rangeHint {
                Text(rangeHint).font(.caption).foregroundStyle(.secondary)
            }
        } header: {
            Text("Signal")
        } footer: {
            Text("Last packet is from whichever node your radio heard last — not a reading for any one link. The noise floor is live: if it climbs when the radio is near a charger or USB hub, that thing is costing you range.")
        }
        .task {
            while !Task.isCancelled {
                let keepGoing = poll.onResult(result: await node.radioStats())
                status = poll.statusLine
                rows = poll.rows.map { ($0.label, $0.value) }
                rangeHint = poll.rangeHint
                if !keepGoing { break }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }
}

struct PresetSheet: View {
    @EnvironmentObject var node: NodeSettingsStore
    @Environment(\.dismiss) private var dismiss
    /// Set for a REMOTE node: the preset goes to it instead of this radio.
    var targetName: String? = nil
    var onApply: ((RadioPresets.Preset) -> Void)? = nil
    @State private var query = ""
    @State private var chosen: RadioPresets.Preset?

    private var grouped: [(region: String, presets: [RadioPresets.Preset])] {
        let all = RadioPresets.shared.ALL.filter {
            query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)
        }
        var order: [String] = []
        var byRegion: [String: [RadioPresets.Preset]] = [:]
        for p in all {
            let r = RadioPresets.shared.region(preset: p)
            if byRegion[r] == nil { order.append(r) }
            byRegion[r, default: []].append(p)
        }
        return order.map { ($0, byRegion[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(RadioPresets.shared.REGULATORY_CAVEAT).font(.caption).foregroundStyle(.red)
                }
                ForEach(grouped, id: \.region) { group in
                    Section(group.region) {
                        ForEach(group.presets, id: \.name) { p in
                            Button { chosen = p } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.name).foregroundStyle(.primary)
                                    Text(p.summary()).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query)
            .navigationTitle("Radio presets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .alert("Apply \(chosen?.name ?? "")\(targetName.map { " to \($0)" } ?? "")?", isPresented: Binding(get: { chosen != nil }, set: { if !$0 { chosen = nil } })) {
                Button("Apply", role: .destructive) {
                    if let p = chosen {
                        if let onApply { onApply(p) } else { node.applyPreset(p) }
                    }
                    chosen = nil
                    dismiss()
                }
                Button("Cancel", role: .cancel) { chosen = nil }
            } message: {
                Text("\(chosen?.summary() ?? "")\n\nEvery node you talk to must be on these same four values. If they aren't, you won't see errors — you'll see an empty mesh.\n\n\(RadioPresets.shared.REGULATORY_CAVEAT)")
            }
        }
    }
}

private struct RadioShareSheet: View {
    @EnvironmentObject var node: NodeSettingsStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let link = node.shareLink, let image = QrCode.image(link) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 280)
                    Text(NodeSettings.shared.shareCaption(region: node.floodScope))
                        .font(.callout)
                        .multilineTextAlignment(.center)
                    Button("Copy link") { UIPasteboard.general.string = link }
                } else {
                    Text("The radio hasn't reported its hop-hash width, so a code can't say it yet. Reconnect and try again.")
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Share radio settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}

// MARK: - Clock

struct ClockSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var reading: (radio: Date, drift: Int64)?
    @State private var asked = false
    @State private var busy = false

    var body: some View {
        Form {
            if !store.isReady {
                NeedsRadio(text: "Connect to a radio to read its clock.")
            } else {
                Section {
                    if busy && reading == nil {
                        HStack { ProgressView(); Text("Querying radio…") }
                    } else if let reading {
                        LabeledContent("Radio clock", value: reading.radio.formatted(date: .abbreviated, time: .standard))
                        LabeledContent("Drift") {
                            Text(NodeSettings.shared.formatDrift(seconds: reading.drift))
                                .foregroundStyle(NodeSettings.shared.isDriftLarge(seconds: reading.drift) ? .red : .secondary)
                        }
                    } else if asked {
                        Text("No answer from the radio.").foregroundStyle(.secondary)
                    }
                    Button("Sync from phone") {
                        Task {
                            busy = true
                            await node.syncClock()
                            await read()
                        }
                    }
                    .disabled(busy)
                    Button("Re-read") { Task { await read() } }
                        .disabled(busy)
                } footer: {
                    Text("The app corrects drift over \(NodeSettings.shared.CLOCK_DRIFT_WARN_SECONDS) s when it connects. Radios without GPS lose their clock on power-cycle, and a skewed clock makes other apps attach your reactions to the wrong message.")
                }
                OutcomeSection()
            }
        }
        .navigationTitle("Clock")
        .task { await read() }
    }

    private func read() async {
        busy = true
        reading = await node.readClock()
        asked = true
        busy = false
    }
}

// MARK: - Mesh policies

struct PoliciesSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var scopeText = ""
    @State private var newRegion = ""
    @State private var discovering = false
    @State private var discovered: RegionDiscovery.Result?
    @State private var forgetting: String?

    var body: some View {
        Form {
            if let p = node.otherParams, store.isReady {
                Section {
                    Toggle("Include location in adverts", isOn: Binding(
                        get: { p.includesLocation },
                        set: { node.setOtherParams(p.withIncludesLocation(on: $0)) }))
                    Toggle("Multi-acks (redundant ACK copies)", isOn: Binding(
                        get: { p.multiAcksOn },
                        set: { node.setOtherParams(p.withMultiAcks(on: $0)) }))
                    Toggle("Flood on the last message retry", isOn: $store.floodFallback)
                } footer: {
                    Text("A message that fails twice is retried once as a flood, after clearing its stored path — MeshCore's documented default. Turning it off means a stale path keeps failing until something else refreshes it.")
                }

                Section {
                    telemetryPicker("Base (battery)", p.base) { node.setOtherParams(p.withBase(mode: $0)) }
                    telemetryPicker("Location", p.location) { node.setOtherParams(p.withLocation(mode: $0)) }
                    telemetryPicker("Environment", p.environment) { node.setOtherParams(p.withEnvironment(mode: $0)) }
                } header: {
                    Text("Telemetry access")
                } footer: {
                    Text("Who may read this node's telemetry: Deny / per-contact flags / everyone.")
                }

                Section {
                    Picker("Bytes per hop", selection: Binding(
                        get: { Int(PathHashMode.shared.modeFor(bytes: node.deviceInfo?.pathHashByteWidth ?? 1)) },
                        set: { node.setPathHashMode($0) })) {
                        ForEach(Array(PathHashMode.shared.LABELS.enumerated()), id: \.offset) { i, label in
                            Text(label).tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("On-air path hash width")
                } footer: {
                    Text("Bytes per hop in packet paths. All nodes on a mesh must match; firmware v10+.")
                }

                Section {
                    if let line = node.radioDefaultScopeLine { Text(line).font(.callout) }
                    TextField("Region (e.g. bayarea)", text: $scopeText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("Set") { node.setFloodScope(scopeText.trimmingCharacters(in: .whitespaces)) }
                            .disabled(!Regions.shared.isValid(raw: scopeText) || node.busy)
                        Spacer()
                        Button("Clear", role: .destructive) { node.setFloodScope(nil) }
                            .disabled(node.busy)
                    }
                    .buttonStyle(.borderless)
                } header: {
                    Text("Global flood scope")
                } footer: {
                    Text("Restricts which repeaters flood a message onward — routing, not privacy: anyone who hears the traffic can still read it. Saved on the radio as its default where the firmware supports it.")
                }

                regionsSection
                OutcomeSection()
            } else {
                NeedsRadio(text: "Connect to a radio to edit mesh policies.")
            }
        }
        .navigationTitle("Mesh policies")
        .onAppear {
            node.refreshSelf()
            node.refreshDevice()
            scopeText = node.floodScope ?? ""
        }
        .sheet(item: Binding(get: { discovered.map(DiscoveredRef.init) }, set: { discovered = $0?.result })) { ref in
            DiscoveredRegionsSheet(result: ref.result)
        }
        .alert("Forget region \(forgetting ?? "")?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } })) {
            Button("Forget", role: .destructive) {
                if let r = forgetting { node.removeRegion(r) }
                forgetting = nil
            }
            Button("Cancel", role: .cancel) { forgetting = nil }
        } message: {
            Text("The name is removed from this phone. Nothing changes on the radio or on any repeater.")
        }
    }

    private var regionsSection: some View {
        Section {
            if let stuck = node.floodScopeStuck {
                Text("The radio may still be scoped to region \(stuck) — restoring the scope after a send failed. Reconnect, or set the flood scope above, before sending again.")
                    .foregroundStyle(.red).font(.callout)
            }
            HStack {
                TextField("Region name (e.g. bayarea)", text: $newRegion)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Add") { node.addRegion(newRegion); newRegion = "" }
                    .disabled(!Regions.shared.isValid(raw: newRegion))
            }
            .buttonStyle(.borderless)
            Button(discovering ? "Asking nearby repeaters…" : "Discover from repeaters…") {
                Task {
                    discovering = true
                    discovered = await node.discoverRegions()
                    discovering = false
                }
            }
            .disabled(discovering)
            if node.regions.isEmpty {
                Text("No regions yet. Add one above, or discover what nearby repeaters know.")
                    .foregroundStyle(.secondary)
            }
            ForEach(node.regions, id: \.self) { r in
                HStack {
                    Text("#\(r)")
                    Spacer()
                    Button("Use") { scopeText = r }
                    Button("Remove", role: .destructive) { forgetting = r }
                }
                .buttonStyle(.borderless)
            }
        } header: {
            Text("Regions")
        } footer: {
            Text("Region names this phone knows, for picking a flood scope. Names are lowercase letters, digits and hyphens.")
        }
    }

    private func telemetryPicker(_ title: String, _ mode: Int32, set: @escaping (Int32) -> Void) -> some View {
        Picker(title, selection: Binding(get: { Int(mode) }, set: { set(Int32($0)) })) {
            ForEach(Array(OtherParams.companion.TELEMETRY_LABELS.enumerated()), id: \.offset) { i, label in
                Text(label).tag(i)
            }
        }
    }

    private struct DiscoveredRef: Identifiable {
        let result: RegionDiscovery.Result
        var id: String { result.names.joined(separator: ",") + "/\(result.asked)/\(result.answered)" }
    }
}

private struct DiscoveredRegionsSheet: View {
    @EnvironmentObject var node: NodeSettingsStore
    @Environment(\.dismiss) private var dismiss
    let result: RegionDiscovery.Result

    var body: some View {
        NavigationStack {
            List {
                Section { Text(result.summary).font(.callout) }
                ForEach(result.names, id: \.self) { name in
                    HStack {
                        Text("#\(name)")
                        Spacer()
                        if node.regions.contains(name) {
                            Text("added").foregroundStyle(.secondary)
                        } else {
                            Button("Add") { node.addRegion(name) }
                        }
                    }
                }
            }
            .navigationTitle("Regions heard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}

// MARK: - Auto-add

struct AutoAddSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore

    var body: some View {
        Form {
            if let flags = node.autoAddFlags, store.isReady {
                Section {
                    bit("Companions (chat)", Codes.shared.AUTO_ADD_CHAT, flags)
                    bit("Repeaters", Codes.shared.AUTO_ADD_REPEATER, flags)
                    bit("Room servers", Codes.shared.AUTO_ADD_ROOM, flags)
                    bit("Sensors", Codes.shared.AUTO_ADD_SENSOR, flags)
                } footer: {
                    Text("Which advert types are added to the contact list automatically when heard.")
                }
                // The app's own rules — the radio has no notion of which
                // repeaters are useful, so they only apply while it isn't
                // adding every one (shared RepeaterAutoAdd, as Android).
                if (Int32(flags) & Codes.shared.AUTO_ADD_REPEATER) == 0 {
                    Section {
                        Toggle("…that relayed messages I received", isOn: $store.autoAddRelaying)
                        Toggle("…heard directly by my radio", isOn: $store.autoAddDirect)
                    } header: {
                        Text("The radio isn't adding repeaters. This app can add the useful ones:")
                    } footer: {
                        Text("\"Relayed\" means its hash is in the routes of \(RepeaterSignals.shared.MIN_RELAYS) or more messages you received in the last week — fewer can be a one-off flood, or another node sharing the hash. A repeater whose short hash another known node shares isn't counted.")
                    }
                }
                Section {
                    bit("Overwrite oldest when full", Codes.shared.AUTO_ADD_OVERWRITE_OLDEST, flags)
                } footer: {
                    Text("Favourites are never overwritten. \"Oldest\" is the node heard from least recently.")
                }
                OutcomeSection()
            } else {
                NeedsRadio(text: "Connect to a radio to read the auto-add policy.")
            }
        }
        .navigationTitle("Auto-add contacts")
        .onAppear { node.refreshAutoAdd() }
    }

    private func bit(_ title: String, _ bit: Int32, _ flags: Int) -> some View {
        Toggle(title, isOn: Binding(
            get: { (Int32(flags) & bit) != 0 },
            set: { node.setAutoAddBit(bit, on: $0) }))
        .disabled(node.busy)
    }
}

// MARK: - Custom variables

struct CustomVarsSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @State private var key = ""
    @State private var value = ""

    var body: some View {
        Form {
            if !store.isReady {
                NeedsRadio(text: "Connect to a radio to read custom variables.")
            } else {
                if node.customVars["gps"] != nil {
                    Section {
                        Toggle("GPS module", isOn: Binding(
                            get: { node.customVars["gps"] == "1" },
                            set: { node.setCustomVar(key: "gps", value: $0 ? "1" : "0") }))
                    }
                }
                Section {
                    if node.customVars.isEmpty {
                        Text("No custom variables reported by this firmware.").foregroundStyle(.secondary)
                    }
                    ForEach(node.customVars.keys.sorted(), id: \.self) { k in
                        Text("\(k): \(node.customVars[k] ?? "")")
                            .font(.system(.callout, design: .monospaced))
                            .textSelection(.enabled)
                    }
                } header: { Text("Reported by the radio") }
                Section {
                    TextField("Variable", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Value", text: $value)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Set") {
                        node.setCustomVar(key: key, value: value)
                        key = ""; value = ""
                    }
                    .disabled(key.trimmingCharacters(in: .whitespaces).isEmpty || node.busy)
                } header: { Text("Set a variable") }
                OutcomeSection()
            }
        }
        .navigationTitle("Custom variables")
        .onAppear { node.refreshCustomVars() }
    }
}

// MARK: - Backup

/// The text of a backup, for the system file exporter.
private struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? ""
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct BackupSpoke: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var exporting = false
    @State private var importing = false
    @State private var withSecrets = false
    @State private var passphrase = ""
    @State private var confirm = ""
    @State private var building = false
    @State private var document: BackupDocument?
    @State private var note: String?
    @State private var restoring: ConfigBackup.Parsed?

    private var minLength: Int { Int(ConfigBackup.shared.MIN_PASSPHRASE_LENGTH) }
    private var canExport: Bool {
        !building && store.isReady && (!withSecrets || (passphrase.count >= minLength && passphrase == confirm))
    }

    var body: some View {
        Form {
            Section {
                Text("A backup is an ordinary file. It leaves every protection this phone gives it, so what goes in is a decision, not a default.")
                    .font(.callout)
            }
            Section {
                Text("Always included: your settings, region names, and the radio's contact list — names and public keys. That is a map of who you talk to. Keep the file somewhere you'd keep that.")
                    .font(.callout)
                Toggle("Include channel keys", isOn: $withSecrets)
                if withSecrets {
                    Text("These are encrypted with your passphrase and nothing else. Anyone who has the file and guesses the passphrase can read every channel you're in. There is no recovery if you forget it.")
                        .font(.callout).foregroundStyle(.red)
                    SecureField("Passphrase (min \(minLength))", text: $passphrase)
                    SecureField("Confirm passphrase", text: $confirm)
                    if !confirm.isEmpty && confirm != passphrase {
                        Text("The passphrases don't match.").font(.caption).foregroundStyle(.red)
                    }
                }
                Button(building ? "Encrypting…" : (withSecrets ? "Export with channel keys" : "Export settings only")) {
                    Task { await build() }
                }
                .disabled(!canExport)
            } header: {
                Text("Export")
            } footer: {
                if !store.isReady { Text("Connect to a radio first: a backup is of a radio's contacts and channels.") }
            }
            Section {
                Button("Import configuration…") { importing = true }
            } header: {
                Text("Restore")
            } footer: {
                Text("Files from the Android app restore here too. Repeater passwords in an Android backup are not used on iOS, which does not store them.")
            }
            if let note {
                Section { Text(note).font(.callout) }
            }
        }
        .navigationTitle("Backup")
        .fileExporter(isPresented: $exporting, document: document, contentType: .data,
                      defaultFilename: store.backupFileName) { result in
            switch result {
            case .success: note = "Backup written."
            case .failure(let e): note = "Backup failed: \(e.localizedDescription)"
            }
            document = nil
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            guard case .success(let url) = result else { return }
            if let parsed = store.readBackup(url) {
                restoring = parsed
            } else {
                note = "That file isn't a MeshCore Hardened backup (or is from a newer version)."
            }
        }
        .sheet(item: Binding(get: { restoring.map(ParsedRef.init) }, set: { restoring = $0?.parsed })) { ref in
            RestoreSheet(parsed: ref.parsed) { message in note = message }
        }
    }

    private func build() async {
        building = true
        defer { building = false }
        switch await store.buildBackup(passphrase: withSecrets ? passphrase : nil) {
        case .success(let text):
            document = BackupDocument(text: text)
            passphrase = ""; confirm = ""
            exporting = true
        case .failure(let e):
            note = e.message
        }
    }

    /// The parsed file's own identity — a fresh UUID per read of the
    /// binding would re-present the sheet on every render.
    private struct ParsedRef: Identifiable {
        let parsed: ConfigBackup.Parsed
        var id: ObjectIdentifier { ObjectIdentifier(parsed) }
    }
}

private struct RestoreSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let parsed: ConfigBackup.Parsed
    let onDone: (String) -> Void
    @State private var settings = true
    @State private var regions = true
    @State private var channels = false
    @State private var contacts = false
    @State private var secrets = false
    @State private var passphrase = ""
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(BackupRestore.companion.summary(parsed: parsed))
                    if BackupRestore.companion.differentRadio(backupSelfKeyHex: parsed.plain.selfKeyHex, currentSelfKeyHex: store.selfKeyHex) {
                        Text("This backup was made on a different radio. Restoring contacts or channels will write them onto the radio connected now.")
                            .foregroundStyle(.red)
                    }
                }
                Section {
                    Toggle("Settings", isOn: $settings)
                    Toggle("Regions", isOn: $regions)
                    Toggle("Channels (writes to the radio)", isOn: $channels)
                    Toggle("Contacts (writes to the radio)", isOn: $contacts)
                    if parsed.hasSecrets {
                        Toggle("Channel keys", isOn: $secrets)
                        if secrets { SecureField("Passphrase", text: $passphrase) }
                    }
                } footer: {
                    if channels || contacts {
                        Text("Writing to the radio can't be undone from here. Channels need their keys, so tick Channel keys too.")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Restore from backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(working ? "Restoring…" : "Restore") {
                        Task {
                            working = true
                            let message = await store.restoreBackup(
                                parsed,
                                options: BackupRestore.Options(settings: settings, regions: regions,
                                                               channels: channels, contacts: contacts, secrets: secrets),
                                passphrase: secrets ? passphrase : nil
                            )
                            working = false
                            onDone(message)
                            dismiss()
                        }
                    }
                    .disabled(working)
                }
            }
        }
    }
}

// MARK: - Stale-node cleanup

/// The only bulk-destructive action in the app: removes nodes from the
/// RADIO's contact list. Which nodes is the shared StaleNodes rule —
/// favourites, never-heard nodes and anything heard or messaged inside
/// the window are never touched — and the list is shown before it runs.
struct StaleNodesSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    @State private var days = Double(StaleNodes.shared.DEFAULT_DAYS)
    @State private var busy = false

    var body: some View {
        let sweep = store.staleSweep(days: Int(days))
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        NavigationStack {
            Form {
                Section {
                    Text(StaleSweep.companion.EXPLAINER)
                    Text(StaleSweep.companion.PROTECTED).font(.callout).foregroundStyle(.secondary)
                }
                Section {
                    Text("Older than \(Int(days)) days")
                    Slider(value: $days,
                           in: Double(StaleNodes.shared.MIN_DAYS)...Double(StaleNodes.shared.MAX_DAYS),
                           step: 1)
                        .disabled(busy)
                    Text("\(sweep.count) of \(sweep.total) nodes").font(.headline)
                    if let kept = StaleNodes.shared.keptNote(sweep: sweep) {
                        Text(kept).font(.callout).foregroundStyle(.secondary)
                    }
                }
                if sweep.count > 0 {
                    Section("Will be removed") {
                        ForEach(StaleSweep.companion.previewLines(sweep: sweep, nowMillis: now), id: \.self) { Text($0) }
                        if let more = StaleSweep.companion.moreLine(sweep: sweep) {
                            Text(more).foregroundStyle(.secondary)
                        }
                    }
                }
                Section { Text(StaleSweep.companion.KEPT_CONVERSATIONS).font(.caption) }
            }
            .navigationTitle("Remove stale nodes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(busy ? "Removing…" : StaleNodes.shared.actionLabel(sweep: sweep), role: .destructive) {
                        Task {
                            busy = true
                            store.nodeActionNote = await store.removeStaleNodes(days: Int(days))
                            busy = false
                            dismiss()
                        }
                    }
                    .disabled(sweep.count == 0 || busy || !store.isReady)
                }
            }
        }
    }
}

// MARK: - Who repeats me

/// Which repeaters carry this radio's adverts — measured by sending one
/// and listening for the copies that come back (shared HeardRepeats).
struct HeardRepeatsView: View {
    @EnvironmentObject var store: MeshCoreStore
    @State private var tick = 0

    var body: some View {
        let _ = tick
        let view = store.repeatRows()
        List {
            Section {
                Text(view.summary)
                Button("Send a flood advert") { store.sendAdvert(flood: true) }
                    .disabled(!store.isReady)
            } footer: {
                Text(store.isReady
                     ? "Repeaters that relay it will appear below as their copies reach this radio. Give it a minute — an advert has to travel out and come back."
                     : "Connect a radio first — this measures what the mesh does with your transmissions, so there has to be one.")
            }
            if !view.rows.isEmpty {
                Section {
                    ForEach(view.rows, id: \.hashHex) { row in repeatRow(row) }
                } footer: {
                    Text(HeardRepeats.shared.CAVEAT)
                }
            }
        }
        .navigationTitle("Who repeats me")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear", role: .destructive) { store.clearHeardRepeats() }
            }
        }
        // Echoes arrive over the next minute or so; re-read while shown.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                tick += 1
            }
        }
    }

    private func repeatRow(_ row: RepeatRow) -> some View {
        let parts: [String] = ["Carried \(row.relayed) of your packet(s)", row.snrText, row.ageText].compactMap { $0 }
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(row.label).font(row.isAmbiguous ? .system(.body, design: .monospaced) : .body)
                if row.isTwoWay {
                    Text("Two-way").font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.green.opacity(0.2), in: Capsule())
                }
            }
            Text(row.direction).font(.caption).foregroundStyle(.secondary)
            Text(parts.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// "Pick on map": move the map until the crosshair is on the spot. Only
/// fills the fields — nothing is sent until Set position (Android's
/// PositionPickerDialog). Uses Apple's map tiles.
private struct PositionPicker: View {
    @Environment(\.dismiss) private var dismiss
    let lat: Double?
    let lon: Double?
    let onPick: (Double, Double) -> Void
    @State private var position: MapCameraPosition = .automatic
    @State private var centre: CLLocationCoordinate2D?

    var body: some View {
        NavigationStack {
            ZStack {
                Map(position: $position)
                    .onMapCameraChange(frequency: .continuous) { centre = $0.region.center }
                Image(systemName: "plus")
                    .font(.title.weight(.light))
                    .allowsHitTesting(false)
            }
            .safeAreaInset(edge: .bottom) {
                Text(centre.map { String(format: "%.5f, %.5f", $0.latitude, $0.longitude) } ?? "Move the map to the spot")
                    .font(.caption.monospacedDigit())
                    .padding(6)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 8)
            }
            .navigationTitle("Pick on map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use this point") {
                        if let c = centre { onPick(c.latitude, c.longitude) }
                        dismiss()
                    }
                    .disabled(centre == nil)
                }
            }
            .onAppear {
                if let lat, let lon, GeoKt.isPlausiblePosition(lat: KotlinDouble(double: lat), lon: KotlinDouble(double: lon)) {
                    position = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                                                          latitudinalMeters: 2000, longitudinalMeters: 2000))
                }
            }
        }
    }
}
