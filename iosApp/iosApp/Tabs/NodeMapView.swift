// In-app node map (MapKit — Apple tiles, so no third-party HTTP;
// the Android side uses osmdroid/OSM instead).
//
// Phase 4 (2026-11-18) brought it toward Android's map: a pin for this
// radio, per-type colours and symbols, a labels switch, type filters,
// "fit all", tapping a pin to open the node, and GPX export (shared
// `Gpx`, which escapes attacker-controlled names). Positions are judged
// by the shared `isPlausiblePosition`, not an inline check. Neighbour
// links come with repeater admin (Phase 5).

import SwiftUI
import MapKit
import Shared

struct NodeMapView: View {
    @EnvironmentObject var store: MeshCoreStore
    @EnvironmentObject var node: NodeSettingsStore
    @AppStorage("mapLabels") private var showLabels = true
    /// Advert types shown, comma-separated; empty = every type (Android's rule).
    @AppStorage("mapTypeFilter") private var filterRaw = ""
    @State private var position: MapCameraPosition = .automatic
    @State private var selected: String?
    @State private var export: ExportFile?

    private var filter: Set<Int32> {
        Set(filterRaw.split(separator: ",").compactMap { Int32($0) })
    }

    private var located: [UiNode] {
        let now = Int64(Date().timeIntervalSince1970)
        return store.nodes.map { UiNode($0, now: now) }.filter { n in
            GeoKt.isPlausiblePosition(lat: n.latitude.map { KotlinDouble(double: $0) },
                                      lon: n.longitude.map { KotlinDouble(double: $0) })
                && (filter.isEmpty || filter.contains(n.type))
        }
    }

    private var selfPoint: CLLocationCoordinate2D? {
        guard let me = node.selfInfo,
              GeoKt.isPlausiblePosition(lat: KotlinDouble(double: me.latitude), lon: KotlinDouble(double: me.longitude))
        else { return nil }
        return CLLocationCoordinate2D(latitude: me.latitude, longitude: me.longitude)
    }

    var body: some View {
        NavigationStack {
            Map(position: $position, selection: $selected) {
                if let me = selfPoint {
                    Marker(showLabels ? "This node (\(store.selfName.isEmpty ? "unnamed" : store.selfName))" : "",
                           systemImage: "location.fill", coordinate: me)
                        .tint(.green)
                }
                ForEach(located) { n in
                    Marker(showLabels ? n.displayName : "", systemImage: Self.symbol(n.type),
                           coordinate: CLLocationCoordinate2D(latitude: n.latitude!, longitude: n.longitude!))
                        .tint(Self.colour(n.type))
                        .tag(n.id)
                }
            }
            .navigationTitle("Map")
            .navigationBarTitleDisplayMode(.inline)
            .overlay {
                if located.isEmpty && selfPoint == nil {
                    Text("No nodes with advertised GPS yet.\nNodes appear here once their adverts include a location.")
                        .multilineTextAlignment(.center)
                        .font(.callout)
                        .padding()
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .overlay(alignment: .bottom) {
                Text("\(located.count) nodes with GPS · Apple Maps tiles")
                    .font(.caption)
                    .padding(6)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 8)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { menu }
            }
            .sheet(item: Binding(get: { selected.flatMap(uiNode(for:)) }, set: { selected = $0?.id })) { n in
                NodeDetailSheet(node: n)
            }
            .sheet(item: $export) { file in ActivityView(items: [file.url]) }
        }
    }

    private var menu: some View {
        Menu {
            Toggle("Show labels", isOn: $showLabels)
            Button("Fit all nodes") { withAnimation { position = .automatic } }
            Section("Show") {
                typeToggle("Only contacts", Codes.shared.ADV_TYPE_CHAT)
                typeToggle("Only repeaters", Codes.shared.ADV_TYPE_REPEATER)
                typeToggle("Only rooms", Codes.shared.ADV_TYPE_ROOM)
                if !filter.isEmpty { Button("Every type") { filterRaw = "" } }
            }
            Button("Export nodes as GPX") { exportGpx() }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
    }

    private func typeToggle(_ title: String, _ type: Int32) -> some View {
        Toggle(title, isOn: Binding(
            get: { filter.contains(type) },
            set: { on in
                var f = filter
                if on { f.insert(type) } else { f.remove(type) }
                filterRaw = f.sorted().map(String.init).joined(separator: ",")
            }))
    }

    private func uiNode(for id: String) -> UiNode? {
        located.first { $0.id == id }
    }

    private func exportGpx() {
        guard let text = store.gpxText() else {
            store.nodeActionProblem = Gpx.shared.NOTHING_TO_EXPORT
            return
        }
        // Replaced on every export: an old file is node names, keys and
        // positions sitting around for no reason.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(Gpx.shared.FILE_NAME)
        do {
            try Data(text.utf8).write(to: url, options: [.atomic, .completeFileProtection])
            export = ExportFile(url: url)
        } catch {
            store.nodeActionProblem = "Couldn't write the GPX file: \(error.localizedDescription)"
        }
    }

    static func colour(_ type: Int32) -> Color {
        switch type {
        case Codes.shared.ADV_TYPE_REPEATER: return .gray
        case Codes.shared.ADV_TYPE_ROOM: return .purple
        case Codes.shared.ADV_TYPE_SENSOR: return .orange
        default: return .blue
        }
    }

    static func symbol(_ type: Int32) -> String {
        switch type {
        case Codes.shared.ADV_TYPE_REPEATER: return "antenna.radiowaves.left.and.right"
        case Codes.shared.ADV_TYPE_ROOM: return "person.3.fill"
        case Codes.shared.ADV_TYPE_SENSOR: return "thermometer.medium"
        default: return "person.fill"
        }
    }
}

private struct ExportFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// The system share sheet, for a file the user chose to export.
private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
