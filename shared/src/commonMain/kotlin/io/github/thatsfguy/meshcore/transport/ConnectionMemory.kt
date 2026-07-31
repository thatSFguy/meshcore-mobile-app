package io.github.thatsfguy.meshcore.transport

/**
 * A persisted record of the transport the app was last *connected* to,
 * so a cold start can re-establish it without the user re-picking and
 * re-tapping Connect every launch. Adapted from reticulum-mobile-app.
 *
 * Only kinds whose reconnect parameters are self-contained are
 * modelled: BLE (MAC address) and TCP (host:port). USB is deliberately
 * excluded — a USB re-attach needs a freshly-granted Android host
 * permission, so it cannot be restored silently.
 */
sealed interface ConnectionMemory {

    val kind: String

    /** A BLE (NUS-over-GATT) MeshCore radio, addressed by MAC. */
    data class Ble(val address: String, val name: String?) : ConnectionMemory {
        override val kind: String get() = KIND_BLE
    }

    /** A TCP-attached (WiFi/Ethernet base-station) radio.
     *  SECURITY: plaintext link — only restored when the TCP transport
     *  toggle is still enabled. */
    data class Tcp(val host: String, val port: Int) : ConnectionMemory {
        override val kind: String get() = KIND_TCP
    }

    companion object {
        const val KIND_BLE = "ble"
        const val KIND_TCP = "tcp"
        const val KIND_USB = "usb" // SavedNode display only; never auto-restored

        /**
         * Resolve the transport to auto-reconnect on launch. Null — come
         * up disconnected — when [autoReconnect] is off, the kind is
         * unknown, its params are malformed, or (TCP) the transport
         * toggle is disabled.
         */
        fun resolve(
            autoReconnect: Boolean,
            kind: String?,
            bleAddress: String?,
            bleName: String?,
            tcpHost: String?,
            tcpPort: Int?,
            tcpEnabled: Boolean,
        ): ConnectionMemory? {
            if (!autoReconnect) return null
            return when (kind) {
                KIND_BLE ->
                    bleAddress?.takeIf { it.isNotBlank() }
                        ?.let { Ble(it, bleName?.ifBlank { null }) }

                KIND_TCP ->
                    if (tcpEnabled && !tcpHost.isNullOrBlank() &&
                        tcpPort != null && tcpPort in 1..65_535
                    ) {
                        Tcp(tcpHost, tcpPort)
                    } else {
                        null
                    }

                else -> null
            }
        }
    }
}

/**
 * One user-saved node in the multi-node connection list. Covers BLE
 * (MAC in [address], [port] null), TCP (host + [port]), and USB
 * (display-only records).
 */
data class SavedNode(
    val kind: String,            // one of ConnectionMemory.KIND_*
    val address: String,         // MAC (BLE) or host (TCP) or device id (USB)
    val port: Int? = null,       // TCP only
    val name: String? = null,
) {
    /** Stable identity for upsert / forget. */
    val key: String get() = "$kind|$address|${port ?: ""}"

    /** One-line storage form. Fields joined by the US control char
     *  (0x1F), which doesn't occur in MACs, hostnames, or names. */
    fun encode(): String =
        listOf(kind, address, port?.toString() ?: "", name ?: "").joinToString(FIELD_SEP)

    companion object {
        private const val FIELD_SEP = "\u001F"

        /** Inverse of [encode]; null for a malformed line. */
        fun decode(line: String): SavedNode? {
            val p = line.split(FIELD_SEP)
            if (p.size < 4) return null
            if (p[0].isBlank() || p[1].isBlank()) return null
            return SavedNode(
                kind = p[0],
                address = p[1],
                port = p[2].toIntOrNull(),
                name = p[3].ifBlank { null },
            )
        }
    }
}
