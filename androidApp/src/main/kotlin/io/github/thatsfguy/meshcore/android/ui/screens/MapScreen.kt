package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
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
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.platform.NodeMarkers
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.presentation.LinkQuality
import io.github.thatsfguy.meshcore.presentation.NeighbourEndpoint
import io.github.thatsfguy.meshcore.presentation.NeighbourLink
import io.github.thatsfguy.meshcore.presentation.neighbourLinks
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * In-app node map (osmdroid + OSM tiles) — the ONLY outbound-HTTP
 * feature (SCOPE.md); tiles cache in app-private storage.
 *
 * Markers are Canvas-drawn pins ([NodeMarkers]): color + glyph per node
 * type (companion/repeater/room/sensor, mirroring the reference
 * client's icon scheme) with always-visible name labels — no tap
 * needed. The ⋮ menu toggles labels and re-fits the view.
 *
 * Tapping a pin opens [MapNodeSheet]; for a repeater that is where its
 * neighbour links are turned on, drawn from the pin to every neighbour
 * this app can place, coloured by the signal the repeater reported.
 */
@Composable
fun MapScreen(vm: MeshCoreViewModel, nav: NavController) {
    val context = LocalContext.current
    val contacts by vm.dbContacts.collectAsState()
    val self by vm.selfInfo.collectAsState()
    val neighbours by vm.neighbourRecords.collectAsState()

    var showLabels by remember { mutableStateOf(true) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var typeFilter by remember { mutableStateOf(vm.prefs.mapTypeFilter) }
    var tilesEnabled by remember { mutableStateOf(vm.prefs.mapTilesEnabled) }

    // The tapped pin, and separately the repeater whose links are drawn.
    // They are not the same state: the whole point of the links is to
    // look at them, which means the sheet that switched them on has to
    // be able to close without taking them with it.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var linksFor by remember { mutableStateOf<String?>(null) }

    val located = contacts.filter {
        val lat = it.latitude
        val lon = it.longitude
        lat != null && lon != null && (kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lon) > 1e-6) &&
            lat in -90.0..90.0 && lon in -180.0..180.0
    }.filter { typeFilter.isEmpty() || it.type in typeFilter }

    // Links are resolved against EVERY contact, not the filtered pins:
    // a neighbour hidden by the type filter is still a node we know, and
    // resolving against the visible subset would report it as unknown.
    val endpoints = contacts.map {
        NeighbourEndpoint(it.keyHex, it.name, it.latitude, it.longitude)
    }
    fun linksOf(key: String): List<NeighbourLink> = neighbourLinks(
        records = neighbours.filter { it.repeaterKeyHex == key },
        nodes = endpoints,
        nowMillis = System.currentTimeMillis(),
    )

    // Two different questions. The popup lists what the repeater heard
    // whether or not it is drawn — a neighbour with no position is still
    // a neighbour, and it can only ever be read there. `linksFor` is the
    // narrower one: which repeater's lines are on the map right now.
    val drawn: List<NeighbourLink> = linksFor?.let { linksOf(it) } ?: emptyList()

    fun toggleType(type: Int) {
        typeFilter = if (type in typeFilter) typeFilter - type else typeFilter + type
        vm.prefs.mapTypeFilter = typeFilter
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Map",
                vm = vm,
                subtitle = "${located.size} nodes with GPS · tiles load over HTTP",
                menuActions = listOfNotNull(
                    MenuAction("Show labels", checked = showLabels) { showLabels = !showLabels },
                    MenuAction("Fit all nodes") { fitRequest++ },
                    linksFor?.let { MenuAction("Hide neighbour links") { linksFor = null } },
                    MenuAction(
                        "Only contacts",
                        checked = io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_CHAT in typeFilter,
                    ) { toggleType(io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_CHAT) },
                    MenuAction(
                        "Only repeaters",
                        checked = io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER in typeFilter,
                    ) { toggleType(io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_REPEATER) },
                    MenuAction(
                        "Only rooms",
                        checked = io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_ROOM in typeFilter,
                    ) { toggleType(io.github.thatsfguy.meshcore.protocol.Codes.ADV_TYPE_ROOM) },
                    MenuAction("Export nodes as GPX") { vm.exportGpx(located.size) },
                    MenuAction("Load tiles (network)", checked = tilesEnabled) {
                        tilesEnabled = !tilesEnabled
                        vm.prefs.mapTilesEnabled = tilesEnabled
                    },
                    MenuAction("Clear tile cache") {
                        runCatching {
                            java.io.File(context.cacheDir, "osmdroid-tiles").deleteRecursively()
                        }
                        vm.transientMessage.value = "Tile cache cleared"
                    },
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
            OsmdroidSetup.apply(context)
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                // Tile fetching is the app's only outbound HTTP; when the
                // user turns it off the map still plots markers on a blank
                // canvas and nothing leaves the device.
                setUseDataConnection(tilesEnabled)
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

        // This tab is the node map and nothing else. A contact's route
        // is drawn in the routing sheet, on the same component the
        // message info sheet uses — see StoredRoutePathMap.
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
        AndroidView(
            factory = { mapView },
            // weight, not fillMaxSize: inside a Column a fillMaxSize
            // child claims the WHOLE height and the banner above it is
            // pushed off screen. weight(1f) gives it what's left.
            modifier = Modifier.fillMaxWidth().weight(1f),
            update = { map ->
                map.setUseDataConnection(tilesEnabled)
                map.overlays.removeAll { it is Marker || it is Polyline }

                val points = ArrayList<GeoPoint>()

                // Lines go on FIRST so the pins they connect stay on top
                // of them — a line drawn over a marker hides the node it
                // is about. Their reading chips go on LAST, above even
                // the name labels: driven on hardware 2026-08-24, a
                // chip under a neighbouring node's label was covered by
                // it, which loses the one number the line exists to
                // report.
                val chips = ArrayList<Marker>()
                val origin = linksFor?.let { key -> contacts.firstOrNull { it.keyHex == key } }
                if (origin?.latitude != null && origin.longitude != null) {
                    val from = GeoPoint(origin.latitude, origin.longitude)
                    for (link in drawn) {
                        val end = link.endpoint ?: continue
                        val to = GeoPoint(end.latitude!!, end.longitude!!)
                        map.overlays.add(linkLine(from, to, link))
                        chips.add(linkChip(map, from, to, link))
                    }
                }

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
                            // A tap is the only way into the node's
                            // details from here; the pin already carries
                            // the name, so an info bubble would just
                            // repeat it.
                            onTap = { selectedKey = c.keyHex },
                        ),
                    )
                }

                map.overlays.addAll(chips)

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

    selectedKey?.let { key ->
        val contact = contacts.firstOrNull { it.keyHex == key }
        if (contact == null) {
            selectedKey = null
        } else {
            MapNodeSheet(
                vm = vm,
                contact = contact,
                links = linksOf(key),
                linksShown = linksFor == key,
                onToggleLinks = { linksFor = if (linksFor == key) null else key },
                onOpenNode = {
                    selectedKey = null
                    nav.navigate("repeater/$key")
                },
                onDismiss = { selectedKey = null },
            )
        }
    }
}

private fun ContactEntity.displayName(): String = name.ifBlank { keyHex.take(8) }

/** The link itself: colour carries the band, width carries it again. */
private fun linkLine(from: GeoPoint, to: GeoPoint, link: NeighbourLink): Polyline =
    Polyline().apply {
        setPoints(listOf(from, to))
        outlinePaint.color = link.quality.argb
        // Redundant with colour on purpose — colour alone is not a
        // reading anyone can take at a glance on a colour-blind screen.
        outlinePaint.strokeWidth = when (link.quality) {
            LinkQuality.Strong -> 8f
            LinkQuality.Good -> 6.5f
            LinkQuality.Fair -> 5f
            LinkQuality.Weak -> 3.5f
            LinkQuality.Marginal -> 2.5f
        }
        // Not clickable: the numbers are on the chip and in the sheet,
        // and a tappable line over a pin steals the pin's tap.
        setOnClickListener { _, _, _ -> false }
    }

/** The dB reading, on the line, at its midpoint. */
private fun linkChip(map: MapView, from: GeoPoint, to: GeoPoint, link: NeighbourLink): Marker {
    val mid = GeoPoint((from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2)
    return Marker(map).apply {
        position = mid
        icon = NodeMarkers.buildChip(map.context, link.mapLabel, link.quality.argb)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "${link.label} · ${link.summary}"
        // Swallow the tap rather than opening osmdroid's bubble over the
        // map: this chip is a label, not a control.
        setOnMarkerClickListener { _, _ -> true }
    }
}

private fun markerFor(
    map: MapView,
    point: GeoPoint,
    type: Int,
    isSelf: Boolean,
    label: String?,
    title: String,
    onTap: (() -> Unit)? = null,
): Marker {
    val pin = NodeMarkers.build(map.context, type, label, isSelf)
    return Marker(map).apply {
        position = point
        icon = pin.drawable
        setAnchor(pin.anchorU, pin.anchorV)
        this.title = title
        // Labels make the tap-bubble redundant, but keep the title for
        // accessibility / long-name truncation.
        if (onTap != null) {
            setOnMarkerClickListener { _, _ ->
                onTap()
                true
            }
        }
    }
}
