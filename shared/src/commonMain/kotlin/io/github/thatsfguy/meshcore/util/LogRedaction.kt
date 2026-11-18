package io.github.thatsfguy.meshcore.util

/**
 * What a diagnostics line may not carry, as one rule for both apps.
 *
 * Moved here from Android's `DiagnosticsLog` (2026-11-18) when iOS got
 * a diagnostics log of its own: two copies of a redaction rule is how
 * one of them ends up leaking. Android's `DiagnosticsLog.redact` and
 * `redactSecrets` now delegate to this, so its existing tests still pin
 * the behaviour, and [LogRedactionTest] pins it here for iOS.
 */
object LogRedaction {

    private val PRV_KEY = Regex("""(set\s+prv\.key\s+)\S+""", RegexOption.IGNORE_CASE)
    private val PASSWORD_CLI = Regex("""(password\s+)\S+""", RegexOption.IGNORE_CASE)
    private val LONG_HEX = Regex("""\b[0-9a-fA-F]{32,}\b""")

    /**
     * A BLE MAC. A permanent hardware identifier, unhelpful in a log
     * someone pastes into an issue. The last two octets are kept so
     * lines about different radios stay distinguishable.
     */
    private val BLE_MAC =
        Regex("""\b(?:[0-9A-Fa-f]{2}:){4}([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})\b""")

    /** Strip anything secret-shaped, and mask MACs, before a line is logged. */
    fun redact(message: String): String =
        redactSecrets(message).replace(BLE_MAC) { "··:··:··:··:${it.groupValues[1]}" }

    /**
     * Strip SECRETS only — for text the app keeps and must still read,
     * such as a stored console reply (a node's `OK - mac: …` answer to
     * `start ota` is read back by the app; masking it broke OTA once).
     */
    fun redactSecrets(message: String): String = message
        .replace(PRV_KEY) { "${it.groupValues[1]}[REDACTED]" }
        .replace(PASSWORD_CLI) { "${it.groupValues[1]}[REDACTED]" }
        .replace(LONG_HEX, "[HEX-REDACTED]")
}
