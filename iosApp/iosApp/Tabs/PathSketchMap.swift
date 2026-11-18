// A small map of one route (iOS Phase 5): how a message arrived, or the
// route a contact is reached by. The sketch — which points are known,
// which were placed approximately, which segments are solid, dotted or
// dashed — is the shared `PathMaps`/`PathSketch`; this only draws it.

import SwiftUI
import MapKit
import Shared

struct PathSketchMap: View {
    let title: String
    let sketch: PathSketch.Sketch

    var body: some View {
        Section {
            Text(sketch.summary()).font(.callout)
            Map(initialPosition: .automatic) {
                ForEach(Array(sketch.segments.enumerated()), id: \.offset) { _, seg in segmentLine(seg) }
                ForEach(Array(sketch.nodes.enumerated()), id: \.offset) { _, node in
                    if let p = node.point {
                        Marker(PathMaps.shared.title(node: node),
                               systemImage: PathMaps.shared.isInferred(node: node) ? "questionmark" : "circle.fill",
                               coordinate: CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude))
                            .tint(node.isEndpoint ? .green : .cyan)
                    }
                }
            }
            .frame(height: 220)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            if let legend = PathMaps.shared.legend(sketch: sketch) {
                Text(legend).font(.caption).foregroundStyle(.secondary)
            }
        } header: {
            Text(title)
        }
    }

    private func segmentLine(_ seg: PathSketch.Segment) -> some MapContent {
        let solid = PathMaps.shared.isSolid(segment: seg)
        let dash: [CGFloat] = solid ? [] : (PathMaps.shared.isDotted(segment: seg) ? [2, 6] : [10, 7])
        let line = [
            CLLocationCoordinate2D(latitude: seg.from.latitude, longitude: seg.from.longitude),
            CLLocationCoordinate2D(latitude: seg.to.latitude, longitude: seg.to.longitude),
        ]
        return MapPolyline(coordinates: line)
            .stroke(Color.cyan.opacity(solid ? 0.9 : 0.55), style: StrokeStyle(lineWidth: 4, lineCap: .round, dash: dash))
    }
}
