package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thatsfguy.meshcore.android.platform.NodeMarkers
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * In-app node map (osmdroid + OSM tiles) — the ONLY outbound-HTTP
 * feature (SCOPE.md); tiles cache in app-private storage.
 *
 * Markers are Canvas-drawn pins ([NodeMarkers]): color + glyph per node
 * type (companion/repeater/room/sensor, mirroring the reference
 * client's icon scheme) with always-visible name labels — no tap
 * needed. The ⋮ menu toggles labels and re-fits the view.
 */
@Composable
fun MapScreen(vm: MeshCoreViewModel) {
    val context = LocalContext.current
    val contacts by vm.dbContacts.collectAsState()
    val self by vm.selfInfo.collectAsState()

    var showLabels by remember { mutableStateOf(true) }
    var fitRequest by remember { mutableIntStateOf(0) }

    val located = contacts.filter {
        val lat = it.latitude
        val lon = it.longitude
        lat != null && lon != null && (kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6) &&
            lat in -90.0..90.0 && lon in -180.0..180.0
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Map",
                vm = vm,
                subtitle = "${located.size} nodes with GPS · tiles load over HTTP",
                menuActions = listOf(
                    MenuAction("Show labels", checked = showLabels) { showLabels = !showLabels },
                    MenuAction("Fit all nodes") { fitRequest++ },
                    MenuAction("Sync contacts") { vm.syncContactsNow() },
                ),
            )
        },
    ) { padding ->
        if (located.isEmpty()) {
            EmptyHint(
                modifier = Modifier.padding(padding),
                text = "No nodes with advertised GPS yet.\nNodes appear here once their adverts include a location.",
            )
            return@Scaffold
        }

        // Restore the last camera so returning to the tab (or relaunching)
        // lands where the user left off instead of re-fitting the world.
        val savedCamera = remember { vm.prefs.mapCamera }
        val mapView = remember {
            Configuration.getInstance().apply {
                userAgentValue = context.packageName
                osmdroidBasePath = java.io.File(context.filesDir, "osmdroid")
                osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid-tiles")
            }
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                if (savedCamera != null) {
                    controller.setZoom(savedCamera.third)
                    controller.setCenter(GeoPoint(savedCamera.first, savedCamera.second))
                } else {
                    controller.setZoom(3.0)
                }
            }
        }
        // A saved camera counts as "already fitted" — only an explicit
        // "Fit all nodes" from the menu overrides the user's view.
        var lastFitHandled by remember { mutableIntStateOf(if (savedCamera != null) 0 else -1) }

        DisposableEffect(Unit) {
            onDispose {
                val center = mapView.mapCenter
                vm.prefs.mapCamera = Triple(
                    center.latitude, center.longitude, mapView.zoomLevelDouble,
                )
                mapView.onDetach()
            }
        }

        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().padding(padding),
            update = { map ->
                map.overlays.removeAll { it is Marker }

                val points = ArrayList<GeoPoint>()

                // This node first (green location-dot pin).
                val selfLat = self?.latitude ?: 0.0
                val selfLon = self?.longitude ?: 0.0
                if (kotlin.math.abs(selfLat) > 1e-6 || kotlin.math.abs(selfLon) > 1e-6) {
                    val p = GeoPoint(selfLat, selfLon)
                    points.add(p)
                    map.overlays.add(
                        markerFor(
                            map, p,
                            type = 0, isSelf = true,
                            label = if (showLabels) (self?.name ?: "This node") else null,
                            title = "This node (${self?.name ?: "me"})",
                        ),
                    )
                }

                for (c in located) {
                    val p = GeoPoint(c.latitude!!, c.longitude!!)
                    points.add(p)
                    map.overlays.add(
                        markerFor(
                            map, p,
                            type = c.type, isSelf = false,
                            label = if (showLabels) c.displayName() else null,
                            title = c.displayName(),
                        ),
                    )
                }

                // Fit on first layout and on menu request.
                if (lastFitHandled != fitRequest) {
                    lastFitHandled = fitRequest
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
                }
                map.invalidate()
            },
        )
    }
}

private fun ContactEntity.displayName(): String = name.ifBlank { keyHex.take(8) }

private fun markerFor(
    map: MapView,
    point: GeoPoint,
    type: Int,
    isSelf: Boolean,
    label: String?,
    title: String,
): Marker {
    val pin = NodeMarkers.build(map.context, type, label, isSelf)
    return Marker(map).apply {
        position = point
        icon = pin.drawable
        setAnchor(pin.anchorU, pin.anchorV)
        this.title = title
        // Labels make the tap-bubble redundant, but keep the title for
        // accessibility / long-name truncation.
    }
}
