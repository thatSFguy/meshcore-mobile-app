package io.github.thatsfguy.meshcore.protocol

/**
 * The radio's BLE pairing PIN.
 *
 * Every rule here is the firmware's, read out of
 * `companion_radio/MyMesh.cpp` rather than assumed — the first cut of
 * this file assumed instead, and got all three of them wrong.
 *
 * **What the firmware accepts** (`CMD_SET_DEVICE_PIN` handler):
 *
 * ```c
 * if (pin == 0 || (pin >= 100000 && pin <= 999999)) { … }
 * else writeErrFrame(ERR_CODE_ILLEGAL_ARG);
 * ```
 *
 * So a PIN is six digits **that do not start with zero**, or the
 * special value 0. "012345" is not a PIN the radio will take; offering
 * it to the user only earns them a rejection.
 *
 * **Zero is not "no PIN".** It clears the stored value so the node
 * falls back to its compiled default: 123456 on a board with no
 * screen, and a fresh random PIN each session on a board that has one
 * to display.
 *
 * **A change needs a reboot.** The handler writes `_prefs.ble_pin` and
 * calls `savePrefs()`, but the PIN actually in force is
 * `_active_ble_pin`, computed once during startup. Until the node
 * restarts it keeps pairing with the old one.
 */
object DevicePin {

    /** What a node without a screen falls back to. */
    const val FACTORY_DEFAULT: String = "123456"

    const val LENGTH: Int = 6

    /** The firmware's accepted range for a real PIN. */
    const val MIN: Int = 100_000
    const val MAX: Int = 999_999

    /** Clears the stored PIN; the node reverts to its built-in default. */
    const val USE_DEFAULT: Int = 0

    /**
     * True if the radio will accept [text] as a new PIN.
     *
     * Six digits, first one non-zero. "000000" is handled separately —
     * see [isUseDefault] — because it means something different from
     * setting a PIN.
     */
    fun isValid(text: String): Boolean {
        if (text.length != LENGTH || !text.all { it in '0'..'9' }) return false
        val n = text.toIntOrNull() ?: return false
        return n in MIN..MAX
    }

    /** "000000" — clear the stored PIN rather than set one. */
    fun isUseDefault(text: String): Boolean =
        text.length == LENGTH && text.all { it == '0' }

    /** Anything the radio will not reject: a real PIN, or the reset. */
    fun isAcceptable(text: String): Boolean = isValid(text) || isUseDefault(text)

    fun isFactoryDefault(text: String): Boolean = text == FACTORY_DEFAULT

    /** The number to send, or null if the radio would reject it. */
    fun parse(text: String): Int? = when {
        isUseDefault(text) -> USE_DEFAULT
        isValid(text) -> text.toIntOrNull()
        else -> null
    }

    /**
     * How to describe the PIN a node reports in DEVICE_INFO.
     *
     * Zero has to be spelled out rather than shown as "000000", which
     * would read as a PIN somebody could type.
     */
    fun describe(pin: Long?): String = when {
        pin == null -> "Unknown — this firmware doesn't report it"
        pin == 0L -> "Not set — the node uses its built-in default"
        pin in MIN.toLong()..MAX.toLong() -> pin.toString()
        else -> "Unrecognised value ($pin)"
    }

    /** Why a typed PIN was refused, for the field's supporting text. */
    fun rejection(text: String): String? = when {
        text.isEmpty() -> null
        text.length != LENGTH -> "A PIN is exactly $LENGTH digits"
        !text.all { it in '0'..'9' } -> "Digits only"
        isUseDefault(text) -> null
        text.first() == '0' -> "The radio won't accept a PIN starting with 0"
        else -> null
    }
}
