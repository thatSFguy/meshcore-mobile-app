// Repeater admin spokes (iOS Phase 5): Settings, Regions, Command help,
// the guest card, and the access-list / neighbour sections on Status.
// What to read, what to send and every sentence are shared
// (RemoteSettingsForm, RegionPanel, CliHelp, RepeaterAdmin, NeighbourBook);
// this file lays them out. A guest can run no CLI at all (CLAUDE.md), so
// the CLI-driven spokes are admin-only by the shared tile rule.

import SwiftUI
import Shared

// MARK: - Guest card

struct GuestCard: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    @State private var owner: String?
    @State private var regions: String?
    @State private var askingOwner = false
    @State private var askingRegions = false

    var body: some View {
        Section {
            ask("Owner", idle: "Owner text and firmware version", answer: owner, busy: askingOwner) {
                askingOwner = true
                owner = await store.ownerLine(node.id)
                askingOwner = false
            }
            ask("Regions", idle: "Which regions it floods", answer: regions, busy: askingRegions) {
                askingRegions = true
                regions = await store.floodRegionsLine(node.id)
                askingRegions = false
            }
        } header: {
            Text("What a guest can read")
        }
    }

    private func ask(_ title: String, idle: String, answer: String?, busy: Bool,
                     action: @escaping () async -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.headline)
                Spacer()
                Button(busy ? "Asking the node…" : (answer == nil ? "Ask the node" : "Ask again")) {
                    Task { await action() }
                }
                .disabled(busy || !store.isReady)
                .buttonStyle(.borderless)
            }
            Text(answer ?? idle).font(.callout).foregroundStyle(answer == nil ? .secondary : .primary)
        }
    }
}

// MARK: - Settings

struct RemoteSettingsView: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    // RemoteSettingsForm is a Kotlin class SwiftUI can't observe; `tick`
    // re-renders after each change to it.
    @State private var form = RemoteSettingsForm()
    @State private var tick = 0
    @State private var status: [String: String] = [:]
    @State private var busy: String?
    @State private var confirm: String?
    @State private var replies: [String] = []
    @State private var rebootNote: String?
    @State private var scanning = false
    @State private var scanned: RemoteScan?
    @State private var presets = false

    private var sections: [RemoteSettingsForm.Section] {
        RemoteSettingsForm.companion.sections(role: RepeaterReadout.shared.roleOf(advType: node.type))
    }

    // Split into small pieces: one body holding all of this was more than
    // the Swift type checker would solve in time (CI, 2026-11-18).
    var body: some View {
        let _ = tick
        Form {
            if !store.isReady {
                Text("Connect to your radio to administer this node.").foregroundStyle(.secondary)
            }
            ForEach(sections, id: \.id) { section in sectionView(section) }
            maintenance
            if let rebootNote { rebootSection(rebootNote) }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $scanning) {
            QrScannerSheet { text in
                scanning = false
                if let s = RemoteScan.companion.decode(text: text) {
                    scanned = s
                } else {
                    status[RemoteSettingsForm.companion.RADIO] = "That isn't a settings code."
                }
            }
        }
        // A preset on the node: saved there, applied when it restarts —
        // so the reboot note shows (shared presetCommands, as Android).
        .sheet(isPresented: $presets) {
            PresetSheet(targetName: node.displayName) { preset in
                run(RemoteSettingsForm.companion.RADIO, RemoteSettingsForm.companion.presetCommands(preset: preset),
                    done: "Saved \(preset.name) on the node")
                rebootNote = preset.name
            }
            .environmentObject(store.node)
        }
        .sheet(item: Binding(get: { scanned.map(ScanRef.init) }, set: { scanned = $0?.scan })) { ref in
            RemoteScanSheet(node: node, scan: ref.scan) { message, reboot in
                status[RemoteSettingsForm.companion.RADIO] = message
                if reboot { rebootNote = "The scanned settings" }
            }
        }
        .onAppear {
            form.seed(id: CliIds.shared.NAME, text: node.name)
            tick += 1
        }
        .alert(confirm ?? "", isPresented: confirmShown) {
            Button("Send", role: .destructive) { sendConfirmed() }
            Button("Cancel", role: .cancel) { confirm = nil }
        } message: {
            Text(RemoteSettingsForm.companion.confirmText(command: confirm ?? ""))
        }
    }

    /// Identity of the Kotlin object itself: a fresh UUID per read of the
    /// binding would make SwiftUI re-present the sheet on every render.
    private struct ScanRef: Identifiable {
        let scan: RemoteScan
        var id: ObjectIdentifier { ObjectIdentifier(scan) }
    }

    private var confirmShown: Binding<Bool> {
        Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })
    }

    private func sendConfirmed() {
        if let c = confirm { run("maint", [c], showReply: true) }
        confirm = nil
    }

    private func sectionView(_ section: RemoteSettingsForm.Section) -> some View {
        Section {
            ForEach(section.switches, id: \.id) { sw in switchRow(section.id, sw) }
            ForEach(section.fields, id: \.id) { field in fieldRow(field) }
            extras(section.id)
            actionRow(section)
            if busy == section.id { HStack { ProgressView(); Text("Talking to node…") } }
            if let note = status[section.id] { Text(note).font(.caption).foregroundStyle(.secondary) }
        } header: {
            Text(section.title)
        } footer: {
            if let hint = section.hint { Text(hint) }
        }
    }

    private func switchRow(_ sectionId: String, _ sw: RemoteSettingsForm.Field) -> some View {
        let reading: Bool? = form.isOn(id: sw.id)?.boolValue
        let binding = Binding<Bool>(
            get: { reading ?? false },
            set: { on in run(sectionId, [form.switchCommand(id: sw.id, on: on)]) }
        )
        return Toggle(sw.label, isOn: binding).disabled(reading == nil || busy != nil)
    }

    @ViewBuilder private func extras(_ sectionId: String) -> some View {
        if sectionId == RemoteSettingsForm.companion.RADIO {
            hashModePicker(sectionId)
            Button("Use a regional preset…") { presets = true }.disabled(busy != nil || !store.isReady)
            Button("Scan settings QR…") { scanning = true }.disabled(busy != nil || !store.isReady)
        }
        if sectionId == RemoteSettingsForm.companion.ADVANCED { loopPicker(sectionId) }
        if sectionId == RemoteSettingsForm.companion.ADVERTS {
            Button("Send advert now") { run(sectionId, ["advert"], done: "Advert sent") }
                .disabled(busy != nil)
        }
    }

    private func actionRow(_ section: RemoteSettingsForm.Section) -> some View {
        HStack {
            Button("Fetch") { fetch(section) }
            Spacer()
            Button("Save") { save(section) }.disabled(!form.isDirty(section: section))
        }
        .buttonStyle(.borderless)
        .disabled(busy != nil || !store.isReady)
    }

    private func rebootSection(_ what: String) -> some View {
        Section {
            Text(RemoteSettingsForm.companion.rebootNote(node: node.displayName, what: what))
            Text(RemoteSettingsForm.companion.REBOOT_WARNING).foregroundStyle(.red)
            Button("Reboot now", role: .destructive) { rebootNow() }
            Button("Later") { rebootNote = nil }
        } header: { Text("Reboot to apply?") }
    }

    private func rebootNow() {
        Task {
            let ok = await store.sendOnly(node.id, "reboot")
            status["maint"] = ok ? "Reboot sent to \(node.displayName)" : "Reboot not sent"
            rebootNote = nil
        }
    }

    @ViewBuilder private func fieldRow(_ field: RemoteSettingsForm.Field) -> some View {
        let binding = Binding(get: { form.value(id: field.id) }, set: { form.edit(id: field.id, text: $0); tick += 1 })
        // Kotlin enums are Swift classes: compare, don't switch.
        if field.isSecret {
            SecureField(field.label, text: binding)
        } else if field.isMultiLine {
            TextField(field.label, text: binding, axis: .vertical).lineLimit(1...4)
        } else if field.isNumeric {
            LabeledContent(field.label) {
                TextField("", text: binding).multilineTextAlignment(.trailing)
                    .keyboardType(field.isInteger ? .numbersAndPunctuation : .decimalPad)
            }
        } else {
            TextField(field.label, text: binding).autocorrectionDisabled()
        }
    }

    private func hashModePicker(_ sectionId: String) -> some View {
        Picker("On-air path hash width", selection: Binding(
            get: { form.pathHashMode().map { Int(truncating: $0) } ?? -1 },
            set: { m in if let c = form.pathHashCommand(mode: Int32(m)) { run(sectionId, [c]) } })) {
            if form.pathHashMode() == nil { Text("Fetch to read").tag(-1) }
            ForEach(Array(PathHashMode.shared.LABELS.enumerated()), id: \.offset) { i, label in Text(label).tag(i) }
        }
    }

    private func loopPicker(_ sectionId: String) -> some View {
        Picker("Loop detection", selection: Binding(
            get: { form.loopMode().map { Int(truncating: $0) } ?? -1 },
            set: { i in if let c = form.loopCommand(index: Int32(i)) { run(sectionId, [c]) } })) {
            if form.loopMode() == nil { Text("Fetch to read").tag(-1) }
            ForEach(Array(RemoteSettingsForm.companion.LOOP_MODES.enumerated()), id: \.offset) { i, m in
                Text(m.capitalized).tag(i)
            }
        }
    }

    private var maintenance: some View {
        Section {
            ForEach(RemoteSettingsForm.companion.MAINTENANCE_READS, id: \.self) { cmd in
                Button(cmd) { run("maint", [cmd], showReply: true) }
            }
            ForEach(RemoteSettingsForm.companion.MAINTENANCE_DESTRUCTIVE, id: \.self) { cmd in
                Button(cmd, role: .destructive) { confirm = cmd }
            }
            if busy == "maint" { HStack { ProgressView(); Text("Talking to node…") } }
            ForEach(Array(replies.enumerated()), id: \.offset) { _, line in
                Text(line).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
            }
            if let note = status["maint"] { Text(note).font(.caption).foregroundStyle(.secondary) }
        } header: {
            Text("Maintenance")
        } footer: {
            Text("Replies appear here. The access list is on the Status screen: `get acl` answers only the node's serial console.")
        }
        .disabled(busy != nil || !store.isReady)
    }

    private func fetch(_ section: RemoteSettingsForm.Section) {
        busy = section.id
        Task {
            var ok = 0
            for id in section.fetchIds {
                let reply = await store.repeaterCli(node.id, command: "get \(id)")
                if form.absorb(id: id, reply: reply) { ok += 1 }
            }
            status[section.id] = ok == 0 ? "No reply — connected and logged in?" : "Refreshed \(ok) value(s)"
            busy = nil
            tick += 1
        }
    }

    private func save(_ section: RemoteSettingsForm.Section) {
        let saves = form.saves(section: section)
        if saves.isEmpty {
            status[section.id] = RemoteSettingsForm.companion.saveResult(sent: 0, ok: 0)
            return
        }
        busy = section.id
        Task {
            var ok = 0
            for cmd in saves.commands {
                if await store.repeaterCli(node.id, command: cmd) != nil { ok += 1 }
            }
            if ok == saves.commands.count {
                form.saved(section: section)
                if let note = saves.rebootNote { rebootNote = note }
            }
            status[section.id] = RemoteSettingsForm.companion.saveResult(sent: Int32(saves.commands.count), ok: Int32(ok))
            busy = nil
            tick += 1
        }
    }

    private func run(_ sectionId: String, _ commands: [String], done: String? = nil, showReply: Bool = false) {
        busy = sectionId
        Task {
            var last: String?
            for cmd in commands {
                last = await store.repeaterCli(node.id, command: cmd)
                if showReply {
                    replies.append(RepeaterAdmin.companion.displayed(line: "$ \(cmd)"))
                    replies.append(RepeaterAdmin.companion.displayed(line: last ?? "(no reply)"))
                }
                if last == nil { break }
            }
            status[sectionId] = last == nil ? "No reply" : (done ?? "Done")
            busy = nil
            tick += 1
        }
    }
}

// MARK: - Regions

struct RemoteRegionsView: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    @State private var tree: Regions.RegionTree?
    @State private var treeReply: String?
    @State private var defaultReply: String?
    @State private var asked = false
    @State private var busy = false
    @State private var note: String?
    @State private var pending = false
    @State private var acting: Regions.RegionEntry?
    @State private var confirmStep: RegionPanel.Step?
    @State private var adding = false
    @State private var newName = ""
    @State private var parent = "*"
    // Untagged traffic: what the firmware supports and what it does now.
    @State private var limitSupported: Bool?
    @State private var hopLimit: Int?
    @State private var chosen: RegionAdmin.Untagged?
    @State private var hops = Int(RegionAdmin.shared.SUGGESTED_HOPS)
    @State private var confirmSteps: [RegionPanel.Step]?

    private var defaultScope: String? { Regions.shared.parseDefaultScope(reply: defaultReply) }

    // Split into small pieces for the Swift type checker (CI, 2026-11-18).
    var body: some View {
        Form {
            topSection
            if let tree { untaggedSection(tree) }
            defaultSection
            treeSection
        }
        .navigationTitle("Regions")
        .task { if !asked { await read() } }
        .confirmationDialog(acting?.name ?? "", isPresented: actingShown, titleVisibility: .visible) { actionButtons }
        .alert(confirmStep?.confirmTitle ?? "", isPresented: stepShown) {
            Button("Confirm", role: .destructive) { confirmOne() }
            Button("Cancel", role: .cancel) { confirmStep = nil }
        } message: {
            Text(confirmStep?.confirmText ?? "")
        }
        .sheet(isPresented: $adding) { addSheet }
        .alert(confirmSteps?.first?.confirmTitle ?? "", isPresented: stepsShown) {
            Button("Refuse", role: .destructive) { confirmMany() }
            Button("Cancel", role: .cancel) { confirmSteps = nil }
        } message: {
            Text(confirmSteps?.first?.confirmText ?? "")
        }
    }

    private var actingShown: Binding<Bool> { Binding(get: { acting != nil }, set: { if !$0 { acting = nil } }) }
    private var stepShown: Binding<Bool> { Binding(get: { confirmStep != nil }, set: { if !$0 { confirmStep = nil } }) }
    private var stepsShown: Binding<Bool> { Binding(get: { confirmSteps != nil }, set: { if !$0 { confirmSteps = nil } }) }

    private func confirmOne() { if let s = confirmStep { run(s) }; confirmStep = nil }
    private func confirmMany() { if let s = confirmSteps { runAll(s) }; confirmSteps = nil }

    private var saveStep: RegionPanel.Step {
        RegionPanel.Step(command: RegionPanel.shared.saveCommand(), confirmTitle: nil, confirmText: nil, done: "Saved")
    }

    private var topSection: some View {
        Section {
            Text(RegionPanel.shared.HINT).font(.callout)
            HStack {
                Button("Fetch regions") { Task { await read() } }
                Spacer()
                Button("Save to node") { run(saveStep) }
            }
            .buttonStyle(.borderless)
            .disabled(busy || !store.isReady)
            if busy { HStack { ProgressView(); Text("Asking the node…") } }
            if pending { Text(RegionPanel.shared.UNSAVED).font(.callout).foregroundStyle(.red) }
            if let note { Text(note).font(.callout) }
        }
    }

    private var defaultSection: some View {
        Section {
            Text(RegionPanel.shared.defaultLine(asked: asked, defaultReply: defaultReply))
            if let d = defaultScope, d != "*" {
                Button("Clear default") { confirmStep = RegionPanel.shared.clearDefault() }.disabled(busy)
            }
        } header: { Text("Default region") }
    }

    private var treeSection: some View {
        Section {
            if asked && tree == nil {
                Text(RegionPanel.shared.NOT_A_TREE).foregroundStyle(.secondary)
                if let raw = treeReply { Text(raw).font(.system(.caption, design: .monospaced)) }
            } else if let tree {
                if tree.regions.isEmpty { Text(RegionPanel.shared.EMPTY).foregroundStyle(.secondary) }
                ForEach(tree.regions, id: \.name) { entry in regionRow(entry) }
                if tree.truncated { Text(RegionPanel.shared.TRUNCATED).font(.caption) }
                Button("Add region") { parent = "*"; adding = true }.disabled(busy)
            }
        } header: { Text("Regions") }
    }

    private func regionRow(_ entry: Regions.RegionEntry) -> some View {
        let tags: String = entry.actionable
            ? RegionPanel.shared.rowTags(entry: entry, defaultScope: defaultScope)
            : RegionPanel.shared.NOT_ACTIONABLE
        let indent = CGFloat(max(0, Int(entry.depth) - 1) * 16)
        return Button { if entry.actionable { acting = entry } } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(.system(.body, design: .monospaced)).foregroundStyle(.primary)
                Text(tags).font(.caption).foregroundStyle(.secondary)
            }
            .padding(.leading, indent)
        }
    }

    @ViewBuilder private var actionButtons: some View {
        if let entry = acting, let tree {
            ForEach(RegionAdmin.shared.actionsFor(entry: entry, tree: tree, defaultScope: defaultScope), id: \.self) { action in
                actionButton(action, entry)
            }
        }
    }

    private func actionButton(_ action: RegionAdmin.Action, _ entry: Regions.RegionEntry) -> some View {
        let role: ButtonRole? = RegionPanel.shared.isRemove(action: action) ? .destructive : nil
        return Button(RegionPanel.shared.label(action: action), role: role) { perform(action, entry) }
    }

    private func perform(_ action: RegionAdmin.Action, _ entry: Regions.RegionEntry) {
        if RegionPanel.shared.isAddChild(action: action) {
            parent = entry.name
            adding = true
        } else if let step = RegionPanel.shared.step(action: action, name: entry.name) {
            if step.confirmTitle != nil { confirmStep = step } else { run(step) }
        }
        acting = nil
    }

    private func currentUntagged(_ tree: Regions.RegionTree) -> RegionAdmin.Untagged? {
        RegionAdmin.shared.currentUntagged(
            wildcardFloodAllowed: tree.wildcardFloodAllowed,
            hopLimit: hopLimit.map { KotlinInt(int: Int32($0)) },
            limitSupported: limitSupported.map { KotlinBoolean(bool: $0) }
        )
    }

    private func untaggedSection(_ tree: Regions.RegionTree) -> some View {
        let current = currentUntagged(tree)
        return Section {
            if current == nil && limitSupported == true {
                Text(RegionPanel.shared.UNTAGGED_UNKNOWN).font(.callout).foregroundStyle(.secondary)
            }
            untaggedPicker(current)
            if RegionPanel.shared.isNearby(mode: chosen ?? current) { nearbyStepper }
            if limitSupported == false { Text(RegionPanel.shared.UNTAGGED_NEEDS_116).font(.caption) }
            Button("Apply") { applyUntagged(tree, current) }
                .disabled(busy || chosen == nil || chosen == current)
        } header: {
            Text("Untagged traffic")
        } footer: {
            Text(RegionPanel.shared.UNTAGGED_HINT)
        }
    }

    private func untaggedPicker(_ current: RegionAdmin.Untagged?) -> some View {
        let choices = RegionPanel.shared.untaggedChoices(limitSupported: limitSupported.map { KotlinBoolean(bool: $0) })
        let selection = Binding<RegionAdmin.Untagged?>(get: { chosen ?? current }, set: { chosen = $0 })
        return Picker("Untagged traffic", selection: selection) {
            ForEach(choices, id: \.self) { m in
                Text(untaggedTitle(m, current)).tag(Optional(m))
            }
        }
        .pickerStyle(.inline)
        .labelsHidden()
    }

    private func untaggedTitle(_ m: RegionAdmin.Untagged, _ current: RegionAdmin.Untagged?) -> String {
        let label: String = RegionPanel.shared.untaggedLabel(mode: m)
        return m == current ? label + "  (now)" : label
    }

    private var nearbyStepper: some View {
        let maxHops = Int(RegionAdmin.shared.MAX_HOPS) - 1
        let title = "\(hops) hop\(hops == 1 ? "" : "s")"
        return VStack(alignment: .leading) {
            Stepper(title, value: $hops, in: 1...maxHops)
            Text(RegionAdmin.shared.describeNearby(hops: Int32(hops))).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func applyUntagged(_ tree: Regions.RegionTree, _ current: RegionAdmin.Untagged?) {
        guard let target = chosen ?? current,
              let steps = RegionPanel.shared.untaggedStep(
                target: target, hops: Int32(hops), wildcardFloodAllowed: tree.wildcardFloodAllowed,
                hopLimit: hopLimit.map { KotlinInt(int: Int32($0)) }, limitSupported: limitSupported == true) else {
            note = "Nothing to change."
            return
        }
        if steps.first?.confirmTitle != nil { confirmSteps = steps } else { runAll(steps) }
    }

    private func runAll(_ steps: [RegionPanel.Step]) {
        busy = true
        Task {
            for step in steps {
                let reply = await store.repeaterCli(node.id, command: step.command)
                if let refused = RegionPanel.shared.refusal(command: step.command, reply: reply) {
                    note = refused
                    busy = false
                    await read()
                    return
                }
                note = step.done
            }
            chosen = nil
            busy = false
            await read()
        }
    }

    private var addSheet: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $newName)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                if !newName.isEmpty && !Regions.shared.isValid(raw: newName) {
                    Text("Lowercase letters, digits and dashes").font(.caption).foregroundStyle(.red)
                }
                Picker("Under", selection: $parent) {
                    ForEach(RegionAdmin.shared.parentChoices(tree: tree), id: \.name) { c in
                        Text(RegionPanel.shared.parentLabel(name: c.name)).tag(c.name)
                    }
                }
            }
            .navigationTitle("Add region")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { adding = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        if let step = RegionPanel.shared.add(name: newName, parent: parent) { run(step) }
                        newName = ""; adding = false
                    }
                    .disabled(!Regions.shared.isValid(raw: newName))
                }
            }
        }
    }

    private func read() async {
        busy = true
        treeReply = await store.repeaterCli(node.id, command: RegionPanel.shared.treeCommand())
        tree = Regions.shared.parseRegionTree(reply: treeReply)
        defaultReply = await store.repeaterCli(node.id, command: RegionPanel.shared.defaultCommand())
        if limitSupported == nil {
            limitSupported = RegionAdmin.shared.supportsHopLimit(verReply: await store.repeaterCli(node.id, command: "ver"))?.boolValue
        }
        if limitSupported == true {
            hopLimit = RegionAdmin.shared.parseHopLimit(reply: await store.repeaterCli(node.id, command: RegionAdmin.shared.GET_HOP_LIMIT))
                .map { Int(truncating: $0) }
        }
        asked = true
        if treeReply == nil { note = "No reply — logged in and in range?" }
        busy = false
    }

    private func run(_ step: RegionPanel.Step) {
        busy = true
        Task {
            let reply = await store.repeaterCli(node.id, command: step.command)
            if let refused = RegionPanel.shared.refusal(command: step.command, reply: reply) {
                note = refused
            } else {
                note = step.done
                if RegionAdmin.shared.savesRegions(command: step.command) { pending = false }
                else if RegionAdmin.shared.needsRegionSave(command: step.command) { pending = true }
            }
            busy = false
            await read()
        }
    }
}

// MARK: - Command help

struct CommandHelpView: View {
    let node: UiNode
    let session: AdminSession
    @State private var query = ""

    private var role: NodeRole { RepeaterReadout.shared.roleOf(advType: node.type) }

    var body: some View {
        let categories = CliHelp.shared.categories(role: role, query: query)
        let total = CliHelp.shared.categories(role: role, query: "").reduce(0) { $0 + $1.commands.count }
        List {
            Section { Text(CliHelp.shared.summary(total: Int32(total), role: role, session: session)) }
            ForEach(categories, id: \.title) { cat in
                Section(cat.title) {
                    ForEach(cat.commands, id: \.id) { cmd in
                        // The console is admin-only, so only an admin's tap
                        // goes there with the command filled in (Android's).
                        if session.isAdmin {
                            NavigationLink { RepeaterConsoleView(node: node, prefill: CliHelp.shared.usage(command: cmd)) } label: { commandRow(cmd) }
                        } else {
                            commandRow(cmd)
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "Search commands")
        .navigationTitle("Command help")
    }

    private func commandRow(_ cmd: CliCommand) -> some View {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(CliHelp.shared.usage(command: cmd))
                                .font(.system(.callout, design: .monospaced))
                                .textSelection(.enabled)
                            let badges = CliHelp.shared.badges(command: cmd)
                            if !badges.isEmpty {
                                Text(badges.joined(separator: " · ")).font(.caption2.bold()).foregroundStyle(.orange)
                            }
                            Text(cmd.description_).font(.caption).foregroundStyle(.secondary)
                        }
    }
}

// MARK: - Status extras

struct AccessListSection: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    @State private var entries: [AccessList.BinEntry]?
    @State private var asked = false
    @State private var busy = false

    var body: some View {
        Section {
            Button(busy ? "Asking the node…" : "Fetch access list") {
                Task {
                    busy = true
                    entries = await store.accessList(node.id)
                    asked = true
                    busy = false
                }
            }
            .disabled(busy || !store.isReady)
            if asked && entries == nil {
                Text(store.repeaterAdmin.lastNote ?? "No reply. The node only answers this to an admin session — a read-only login is not enough.")
                    .font(.callout).foregroundStyle(.secondary)
            } else if let entries {
                if entries.isEmpty { Text("The node answered and reported no access-list entries.").font(.callout) }
                ForEach(entries, id: \.keyPrefixHex) { e in
                    HStack {
                        Text(e.keyPrefixHex).font(.system(.callout, design: .monospaced))
                        Spacer()
                        Text(e.roleLabel).foregroundStyle(.secondary)
                    }
                }
            }
        } header: {
            Text("Access list")
        } footer: {
            Text("Prefixes, not full keys — a prefix identifies a node only as far as it goes. Editing the list is done from the node's own console.")
        }
    }
}

struct NeighboursSection: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    let isAdmin: Bool
    @State private var busy = false
    @State private var probing = false
    @State private var note: String?

    var body: some View {
        let links = store.neighbourLinks(node.id)
        Section {
            HStack {
                Button(links.isEmpty ? "Fetch neighbours" : "Fetch again") {
                    Task { busy = true; note = await store.collectNeighbours(node.id); busy = false }
                }
                Spacer()
                if isAdmin {
                    Button("Probe") {
                        Task { probing = true; note = await store.probeNeighbours(node.id); probing = false }
                    }
                }
            }
            .buttonStyle(.borderless)
            .disabled(busy || probing || !store.isReady)
            if busy { HStack { ProgressView(); Text("Asking the node…") } }
            if probing {
                HStack { ProgressView(); Text("Asking nearby repeaters to answer… This takes about a minute.") }
            }
            if let note { Text(note).font(.callout) }
            ForEach(links, id: \.keyPrefixHex) { link in
                VStack(alignment: .leading, spacing: 2) {
                    Text(link.label)
                    Text(link.undrawable.map { "\(link.summary) · \($0.reason)" } ?? link.summary)
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if !links.isEmpty {
                Button("Forget this reading", role: .destructive) { store.forgetNeighbours(node.id) }
            }
        } header: {
            Text("Neighbours")
        } footer: {
            Text("Other repeaters this one heard directly — only as fresh as their last advert. Nothing keeps this list current; Probe asks the node to listen again.")
        }
    }
}

struct TelemetrySection: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    @State private var view: RepeaterAdmin.TelemetryView?
    @State private var busy = false

    var body: some View {
        Section("Telemetry") {
            Button(busy ? "Waiting for the node…" : "Fetch telemetry") {
                Task { busy = true; view = await store.telemetry(node.id, type: node.type); busy = false }
            }
            .disabled(busy || !store.isReady)
            if let message = view?.message { Text(message).font(.callout).foregroundStyle(.secondary) }
            ForEach(view?.rows ?? [], id: \.label) { row in LabeledContent(row.label, value: row.value) }
        }
    }
}

struct NoiseWatchSection: View {
    @EnvironmentObject var store: MeshCoreStore
    let node: UiNode
    @State private var watching = false
    @State private var samples: [Int] = []

    var body: some View {
        Section("Noise floor") {
            HStack {
                Button(watching ? "Stop watching" : "Watch noise floor") { watching.toggle() }
                    .disabled(!store.isReady)
                Spacer()
                if !samples.isEmpty { Button("Clear") { samples = [] } }
            }
            .buttonStyle(.borderless)
            if let readout = NoiseWatch.shared.readout(samples: samples.map { KotlinInt(int: Int32($0)) }) {
                Text(readout).font(.callout)
                Text(samples.map(String.init).joined(separator: " "))
                    .font(.system(.caption2, design: .monospaced)).foregroundStyle(.secondary)
            } else {
                Text(NoiseWatch.shared.IDLE_HINT).font(.caption).foregroundStyle(.secondary)
            }
        }
        // Polls only while on and only while this screen is shown.
        .task(id: watching) {
            while watching && !Task.isCancelled {
                if let n = await store.noiseFloor(node.id) {
                    samples = NoiseWatch.shared.add(samples: samples.map { KotlinInt(int: Int32($0)) }, reading: Int32(n))
                        .map { Int(truncating: $0) }
                }
                try? await Task.sleep(nanoseconds: UInt64(NoiseWatch.shared.INTERVAL_MS) * 1_000_000)
            }
        }
    }
}

/// A scanned settings code applied to this repeater (shared RemoteScan):
/// read what it runs, say what would change, write only on Apply.
struct RemoteScanSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss
    let node: UiNode
    let scan: RemoteScan
    let onDone: (_ message: String, _ needsReboot: Bool) -> Void
    @State private var reading = true
    @State private var writeRegions = false
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(scan.name).font(.headline)
                    Text(scan.summary)
                    if reading {
                        HStack { ProgressView(); Text("Checking what \(node.displayName) has now…") }
                    } else {
                        Text(scan.radioLine(node: node.displayName)).font(.callout)
                    }
                }
                Section { Text(scan.warning).font(.callout).foregroundStyle(.red) }
                if !reading { regionSection }
            }
            .navigationTitle("Apply to \(node.displayName)?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(scan.nothingToDo(writeRegions: writeRegions) ? "Close" : "Apply") { apply() }
                        .disabled(reading || working)
                }
            }
            .task { await read() }
        }
    }

    @ViewBuilder private var regionSection: some View {
        if let line = scan.regionLine(node: node.displayName) {
            Section {
                if scan.canWriteRegions {
                    Toggle("Also update \(node.displayName)'s regions:", isOn: $writeRegions)
                    ForEach(scan.regionChanges, id: \.self) { Text("• \($0)").font(.callout) }
                }
                Text(line).font(.caption).foregroundStyle(.secondary)
            } header: { Text("Regions") }
        }
    }

    private func read() async {
        for cmd in scan.readCommands() {
            scan.take(command: cmd, reply: await store.repeaterCli(node.id, command: cmd))
        }
        reading = false
    }

    private func apply() {
        let commands = scan.commands(writeRegions: writeRegions)
        if commands.isEmpty { dismiss(); return }
        working = true
        Task {
            var message = "Settings saved on \(node.displayName)"
            for cmd in commands {
                let reply = await store.repeaterCli(node.id, command: cmd)
                if let refused = scan.refusal(node: node.displayName, command: cmd, reply: reply) {
                    message = refused
                    break
                }
            }
            working = false
            onDone(message, scan.needsReboot && !message.contains("didn't take"))
            dismiss()
        }
    }
}
