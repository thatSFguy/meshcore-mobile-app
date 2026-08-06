package io.github.thatsfguy.meshcore.android.ui.screens

import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

/**
 * osmdroid's process-wide configuration, in one place.
 *
 * osmdroid keeps this in a singleton, so whichever map is built FIRST
 * decides whether tiles work for every map afterwards. That is a trap:
 * the Map tab used to set it inline, so the message-route map only drew
 * tiles if you happened to have opened the Map tab earlier in the same
 * process — and silently drew an empty grid if you had not.
 *
 * The user agent is the part that actually matters. OpenStreetMap
 * rejects requests carrying osmdroid's default, so an unconfigured map
 * does not fail loudly; it just never receives a tile.
 *
 * Cheap and idempotent, so every map calls it before construction
 * rather than assuming someone else did.
 */
object OsmdroidSetup {
    fun apply(context: Context) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
        }
    }
}
