package io.github.thatsfguy.meshcore.protocol

/**
 * The radio's BLE pairing PIN.
 *
 * Nodes without a screen ship with **123456**. That is documented,
 * public, and identical on every such node, so until it is changed the
 * PIN is not a secret and anyone in Bluetooth range can pair with the
 * radio — which means reading its contacts, its messages and its
 * settings.
 *
 * Validation lives here rather than in the screen because the wire
 * form is a number and the typed form is six characters, and the two
 * differ in a way that matters: "000123" is a perfectly good PIN whose
 * numeric value is 123, so anything that round-trips through an Int
 * and back to text has to re-pad it or it will show the user a
 * different PIN than the radio has.
 */
object DevicePin {

    /** What a node without a screen ships with. */
    const val FACTORY_DEFAULT: String = "123456"

    /** BLE passkeys are exactly six decimal digits. */
    const val LENGTH: Int = 6

    fun isValid(text: String): Boolean =
        text.length == LENGTH && text.all { it in '0'..'9' }

    fun isFactoryDefault(text: String): Boolean = text == FACTORY_DEFAULT

    /** The number to send, or null if [text] is not a valid PIN. */
    fun parse(text: String): Int? =
        if (isValid(text)) text.toIntOrNull() else null

    /** Render a numeric PIN back as six digits, leading zeros intact. */
    fun format(pin: Int): String = pin.toString().padStart(LENGTH, '0')
}
