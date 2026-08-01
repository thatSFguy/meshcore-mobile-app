package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Pick a position by moving the map under a fixed crosshair.
 *
 * Crosshair-and-centre rather than tap-to-drop: a tap target on a map is
 * a few metres wide at useful zoom levels, and the position being chosen
 * here is the one the radio broadcasts to the whole mesh.
 *
 * Tiles are the app's only outbound HTTP, and this respects the same
 * off-switch as the map tab — with the switch off the picker still
 * works, just against a blank canvas with coordinates.
 */
@Composable
fun PositionPickerDialog(
    vm: MeshCoreViewModel,
    initialLat: Double,
    initialLon: Double,
    onPick: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val tilesEnabled = vm.prefs.mapTilesEnabled

    // A radio with no fix reports 0,0. Starting the map there drops the
    // user in the Atlantic; start from the last map camera instead.
    val start = remember {
        val hasFix = kotlin.math.abs(initialLat) > 1e-6 || kotlin.math.abs(initialLon) > 1e-6
        when {
            hasFix -> Triple(initialLat, initialLon, 13.0)
            else -> vm.prefs.mapCamera ?: Triple(0.0, 0.0, 2.0)
        }
    }

    var centre by remember { mutableStateOf(start.first to start.second) }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = java.io.File(context.filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid-tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setUseDataConnection(tilesEnabled)
            setMultiTouchControls(true)
            controller.setZoom(start.third)
            controller.setCenter(GeoPoint(start.first, start.second))
        }
    }

    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a position") },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { mapView },
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        update = { map ->
                            map.setUseDataConnection(tilesEnabled)
                            map.addMapListener(
                                object : org.osmdroid.events.MapListener {
                                    override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean {
                                        centre = map.mapCenter.latitude to map.mapCenter.longitude
                                        return false
                                    }

                                    override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean {
                                        centre = map.mapCenter.latitude to map.mapCenter.longitude
                                        return false
                                    }
                                },
                            )
                        },
                    )
                    Text("✛", style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    "%.5f, %.5f".format(centre.first, centre.second),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "This position is broadcast to the whole mesh in your adverts — " +
                        "anyone in radio range, and anyone they relay to, can see it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!tilesEnabled) {
                    Text(
                        "Map tiles are off, so the canvas stays blank — the coordinates " +
                            "above still track the crosshair.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(centre.first, centre.second) }) { Text("Use this") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
