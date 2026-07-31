// In-app node map (MapKit — Apple tiles, so no third-party HTTP;
// the Android side uses osmdroid/OSM instead).

import SwiftUI
import MapKit

struct NodeMapView: View {
    @EnvironmentObject var store: MeshCoreStore

    private var located: [UiContact] {
        store.contacts.filter { c in
            guard let lat = c.latitude, let lon = c.longitude else { return false }
            return (abs(lat) > 1e-6 || abs(lon) > 1e-6)
                && (-90.0...90.0).contains(lat) && (-180.0...180.0).contains(lon)
        }
    }

    var body: some View {
        NavigationStack {
            Map {
                ForEach(located) { contact in
                    Marker(
                        contact.name.isEmpty ? String(contact.id.prefix(8)) : contact.name,
                        coordinate: CLLocationCoordinate2D(
                            latitude: contact.latitude!,
                            longitude: contact.longitude!
                        )
                    )
                }
            }
            .navigationTitle("Node map")
            .overlay(alignment: .bottom) {
                Text("\(located.count) nodes with advertised GPS")
                    .font(.caption)
                    .padding(6)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 8)
            }
        }
    }
}
