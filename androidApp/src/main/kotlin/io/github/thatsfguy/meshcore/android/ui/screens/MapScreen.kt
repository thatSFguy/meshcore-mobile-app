package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.protocol.Codes
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * In-app node map (osmdroid + OSM tiles). This is the ONLY feature that
 * makes outbound HTTP (SCOPE.md): tiles are lazy-loaded on pan/zoom and
 * cached on disk by osmdroid, so repeat views are served offline.
 * Markers come from advertised GPS in the (verified) contact records —
 * no location ever leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: MeshCoreViewModel) {
    val context = LocalContext.current
    val contacts by vm.dbContacts.collectAsState()
    val self by vm.selfInfo.collectAsState()

    val located = contacts.filter {
        val lat = it.latitude
        val lon = it.longitude
        lat != null && lon != null && (kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6) &&
            lat in -90.0..90.0 && lon in -180.0..180.0
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Node map")
                    Text(
                        "${located.size} nodes with advertised GPS · tiles load over HTTP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
    ) { padding ->
        val mapView = remember {
            // osmdroid needs a user agent for the OSM tile policy; the
            // package name is the recommended value. Tile cache lives in
            // app-private storage (no external-storage permission).
            Configuration.getInstance().apply {
                userAgentValue = context.packageName
                osmdroidBasePath = java.io.File(context.filesDir, "osmdroid")
                osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid-tiles")
            }
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(3.0)
            }
        }

        DisposableEffect(Unit) {
            onDispose { mapView.onDetach() }
        }

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().padding(padding),
            update = { map ->
                map.overlays.removeAll { it is Marker }

                val points = ArrayList<GeoPoint>()
                val selfLat = self?.latitude ?: 0.0
                val selfLon = self?.longitude ?: 0.0
                if (kotlin.math.abs(selfLat) > 1e-6 || kotlin.math.abs(selfLon) > 1e-6) {
                    val p = GeoPoint(selfLat, selfLon)
                    points.add(p)
                    map.overlays.add(
                        Marker(map).apply {
                            position = p
                            title = "This node (${self?.name ?: "me"})"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        },
                    )
                }
                for (c in located) {
                    val p = GeoPoint(c.latitude!!, c.longitude!!)
                    points.add(p)
                    map.overlays.add(
                        Marker(map).apply {
                            position = p
                            title = c.name.ifBlank { c.keyHex.take(12) }
                            snippet = when (c.type) {
                                Codes.ADV_TYPE_REPEATER -> "Repeater"
                                Codes.ADV_TYPE_ROOM -> "Room server"
                                Codes.ADV_TYPE_SENSOR -> "Sensor"
                                else -> "Contact"
                            }
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        },
                    )
                }
                if (points.size == 1) {
                    map.controller.setCenter(points[0])
                    map.controller.setZoom(11.0)
                } else if (points.size > 1) {
                    map.post {
                        runCatching {
                            map.zoomToBoundingBox(
                                BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.3f),
                                false,
                            )
                        }
                    }
                }
                map.invalidate()
            },
        )
    }
}
