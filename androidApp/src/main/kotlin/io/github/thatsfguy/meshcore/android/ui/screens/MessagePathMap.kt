package io.github.thatsfguy.meshcore.android.ui.screens

import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.PathSketch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The route a received message took, on a map.
 *
 * Sits above the ordered "Arrived via" list in the message info sheet —
 * the list is the authority on what the chain was, the map is the shape
 * of it. Where a node cannot be placed, the LIST still names it, so
 * nothing is lost by the map being unable to draw it.
 *
 * Three visual states, matching [PathSketch]:
 *
 *  - solid line, filled pin — the node advertised this position;
 *  - dotted line, hollow pin with `?` — we know which node it is but
 *    not where, so it is drawn approximately as a drawing aid;
 *  - dashed line, no pin at all — we could not identify the hop, and
 *    dashed is what this app's map already uses for "no route is being
 *    claimed here".
 *
 * The map is skipped entirely when nothing can be placed: an empty
 * grey rectangle communicates less than the sentence that replaces it.
 */
@Composable
fun MessagePathMap(vm: MeshCoreViewModel, m: MessageEntity, senderLabel: String) {
    val contacts by vm.dbContacts.collectAsState()
    val self by vm.selfInfo.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()

    val sketch = remember(m.id, contacts, self, deviceInfo) {
        vm.sketchArrival(m, senderLabel)
    }
    if (sketch == null || sketch.isEmpty) return

    val context = LocalContext.current
    val tilesEnabled = vm.prefs.mapTilesEnabled
    val mapView = remember {
        // Without this the tiles never arrive — see OsmdroidSetup.
        OsmdroidSetup.apply(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(tilesEnabled)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Route", style = MaterialTheme.typography.titleSmall)
    Text(
        sketch.summary(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    // clipToBounds is load-bearing. A MapView draws its tiles AND its
    // overlays across the whole canvas it is given and does not clip
    // itself, so without this the route lines and pins paint straight
    // over the rows above and below — and a pinch-zoom sends them all
    // over the sheet.
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .clipToBounds(),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            update = { map ->
                map.setUseDataConnection(tilesEnabled)
                map.overlays.removeAll { it is Marker || it is Polyline }

                for (segment in sketch.segments) {
                    map.overlays.add(
                        Polyline(map).apply {
                            setPoints(
                                listOf(
                                    GeoPoint(segment.from.latitude, segment.from.longitude),
                                    GeoPoint(segment.to.latitude, segment.to.longitude),
                                ),
                            )
                            outlinePaint.strokeWidth = 7f
                            outlinePaint.color = when (segment.style) {
                                PathSketch.Style.Solid -> AndroidColor.argb(220, 0x4F, 0xC3, 0xF7)
                                // Fainter for anything not fully vouched for.
                                else -> AndroidColor.argb(140, 0x4F, 0xC3, 0xF7)
                            }
                            outlinePaint.style = Paint.Style.STROKE
                            outlinePaint.pathEffect = when (segment.style) {
                                PathSketch.Style.Solid -> null
                                // Dotted = "placed approximately".
                                PathSketch.Style.Dotted ->
                                    DashPathEffect(floatArrayOf(3f, 9f), 0f)
                                // Dashed = "no route claimed" — the
                                // meaning the main map already gives it.
                                PathSketch.Style.Dashed ->
                                    DashPathEffect(floatArrayOf(18f, 12f), 0f)
                            }
                        },
                    )
                }

                for (node in sketch.nodes) {
                    val point = node.point ?: continue
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(point.latitude, point.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = when (node.certainty) {
                                PathSketch.Certainty.Inferred ->
                                    "${node.label} — approximate (position unknown)"
                                else -> node.label
                            }
                            icon = PathPins.forNode(
                                context,
                                inferred = node.certainty == PathSketch.Certainty.Inferred,
                                isEndpoint = node.isEndpoint,
                            )
                        },
                    )
                }

                val points = sketch.nodes.mapNotNull { it.point }
                    .map { GeoPoint(it.latitude, it.longitude) }
                if (points.size == 1) {
                    map.controller.setZoom(13.0)
                    map.controller.setCenter(points.first())
                } else if (points.size > 1) {
                    val box = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(points)
                    map.post {
                        runCatching { map.zoomToBoundingBox(box, false, 96) }
                        // Two nodes a few hundred metres apart fit at a
                        // zoom past MAPNIK's last level, where there are
                        // no tiles to draw and osmdroid shows its empty
                        // placeholder grid — which looks exactly like
                        // "tiles are off".
                        if (map.zoomLevelDouble > MAX_ROUTE_ZOOM) {
                            map.controller.setZoom(MAX_ROUTE_ZOOM)
                        }
                    }
                }
                map.invalidate()
            },
        )
    }
    if (sketch.inferred > 0 || sketch.unplaced > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            PathSketch.LEGEND,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** MAPNIK's deepest usable level; past it the map is a blank grid. */
private const val MAX_ROUTE_ZOOM = 17.0
