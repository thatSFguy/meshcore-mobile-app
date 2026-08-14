package io.github.thatsfguy.meshcore.firmware

import io.github.thatsfguy.meshcore.model.DeviceInfo

/**
 * One line describing what the connected radio is running, e.g.
 * `Heltec T114 · v1.17.0 (13 Aug 2026)`.
 *
 * Every part is optional: firmware older than the fields themselves
 * reports none of them (see `DeviceIdentityTest`), and a board with no
 * manufacturer name reports only a version. Null means there is nothing
 * worth showing — the caller draws no row rather than an empty one.
 */
fun deviceIdentityLine(info: DeviceInfo?): String? {
    if (info == null) return null
    val head = listOfNotNull(info.boardName, info.firmwareVersion).joinToString(" · ")
    if (head.isEmpty()) return null
    val built = info.firmwareBuildDate
    return if (built.isNullOrEmpty()) head else "$head ($built)"
}
