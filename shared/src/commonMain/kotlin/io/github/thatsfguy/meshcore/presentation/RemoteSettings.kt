package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CliFormFields
import io.github.thatsfguy.meshcore.protocol.CliIds
import io.github.thatsfguy.meshcore.protocol.CliReplies
import io.github.thatsfguy.meshcore.protocol.CliValues
import io.github.thatsfguy.meshcore.protocol.NodeRole
import io.github.thatsfguy.meshcore.protocol.PathHashMode
import io.github.thatsfguy.meshcore.protocol.RadioUnits

/**
 * A repeater's settings form, as data: which values each section reads,
 * how a `get` reply becomes a field, and which `set` commands an edit
 * becomes — with every value validated before it is sent.
 *
 * Android's RemoteSettingsForm.kt carried all of this inside Compose.
 * Lifted for iOS Phase 5 (2026-11-18), fixing two things on the way:
 * owner text keeps its line breaks (the firmware's `|`; Android flattened
 * them on read and sent the flattened text back), and the forwarding
 * delays are checked as integers instead of sent raw.
 */
class RemoteSettingsForm {

    enum class Kind { Text, MultiLine, Integer, Decimal, Secret }

    data class Field(val id: String, val label: String, val kind: Kind) {
        // For Swift, which must not spell Kotlin enum entries (renames).
        val isSecret: Boolean get() = kind == Kind.Secret
        val isMultiLine: Boolean get() = kind == Kind.MultiLine
        val isInteger: Boolean get() = kind == Kind.Integer
        val isNumeric: Boolean get() = kind == Kind.Integer || kind == Kind.Decimal
    }

    data class Section(
        val id: String,
        val title: String,
        /** What `get` reads for this section. */
        val fetchIds: List<String>,
        val fields: List<Field>,
        /** Switches applied the moment they're flipped (`set <id> on|off`). */
        val switches: List<Field>,
        val hint: String?,
    )

    /** What a Save sends, and whether the node then needs a reboot. */
    data class Saves(val commands: List<String>, val rebootNote: String?) {
        val isEmpty: Boolean get() = commands.isEmpty()
    }

    private val values = HashMap<String, String>()
    private val dirty = HashSet<String>()

    fun value(id: String): String = values[id].orEmpty()

    fun edit(id: String, text: String) {
        values[id] = text
        dirty += id
    }

    fun isDirty(section: Section): Boolean = (section.fields.map { it.id }).any { it in dirty }

    /** Seed a field the node hasn't answered for yet (name, position from the contact). */
    fun seed(id: String, text: String) {
        if (id !in values) values[id] = text
    }

    /**
     * Take a `get [id]` reply into the form. Returns whether a value was
     * read. Radio splits into its four fields; owner text is decoded.
     */
    fun absorb(id: String, reply: String?): Boolean {
        val value = reply?.let { CliReplies.extractGetValue(it) } ?: return false
        when (id) {
            CliIds.RADIO -> {
                val r = CliReplies.parseRadioCsv(value) ?: return false
                // The node prints float32 at full precision (910.5250244);
                // tidyDecimal shortens only when it is the same number.
                values[CliFormFields.RADIO_FREQ] = RadioUnits.tidyDecimal(r.freqMhz.toString())
                values[CliFormFields.RADIO_BW] = RadioUnits.tidyDecimal(r.bwKhz.toString())
                values[CliFormFields.RADIO_SF] = r.sf.toString()
                values[CliFormFields.RADIO_CR] = r.cr.toString()
                dirty -= CliFormFields.RADIO_FIELDS.toSet()
            }
            CliIds.OWNER_INFO -> { values[id] = CliValues.decodeOwnerInfo(value); dirty -= id }
            CliIds.DUTYCYCLE -> { values[id] = value.trim().removeSuffix("%").trim(); dirty -= id }
            else -> { values[id] = value; dirty -= id }
        }
        return true
    }

    /** The current on/off reading of a switch, or null when not read. */
    fun isOn(id: String): Boolean? = values[id]?.let { CliReplies.isTruthy(it) }

    /** The command a switch sends; multi-acks takes 1/0, the rest on/off. */
    fun switchCommand(id: String, on: Boolean): String {
        val word = if (id == CliIds.MULTI_ACKS) CliValues.oneZero(on) else CliValues.onOff(on)
        values[id] = word
        return "set $id $word"
    }

    /** The node's hop-hash mode (0..2), when read and valid. */
    fun pathHashMode(): Int? = values[CliIds.PATH_HASH_MODE]?.trim()?.toIntOrNull()?.takeIf { PathHashMode.isValid(it) }

    fun pathHashCommand(mode: Int): String? {
        if (!PathHashMode.isValid(mode)) return null
        values[CliIds.PATH_HASH_MODE] = mode.toString()
        return "set ${CliIds.PATH_HASH_MODE} $mode"
    }

    /** Index into [LOOP_MODES] of what the node reported; null when unread or unrecognised. */
    fun loopMode(): Int? = values[CliIds.LOOP_DETECT]?.let { CliValues.parseLoopDetect(it) }

    fun loopCommand(index: Int): String? {
        val mode = LOOP_MODES.getOrNull(index) ?: return null
        values[CliIds.LOOP_DETECT] = mode
        return "set ${CliIds.LOOP_DETECT} $mode"
    }

    /** What Save sends for [section]: only edited fields, only valid values. */
    fun saves(section: Section): Saves {
        val out = ArrayList<String>()
        var reboot: String? = null
        fun edited(id: String) = id in dirty
        fun text(id: String) = values[id].orEmpty().trim()
        when (section.id) {
            BASIC -> {
                if (edited(CliIds.NAME) && text(CliIds.NAME).isNotEmpty()) out += "set name ${text(CliIds.NAME)}"
                if (edited(CliIds.OWNER_INFO)) out += "set owner.info ${CliValues.encodeOwnerInfo(text(CliIds.OWNER_INFO))}"
            }
            RADIO -> {
                if (CliFormFields.RADIO_FIELDS.any { edited(it) }) {
                    val f = text(CliFormFields.RADIO_FREQ).toDoubleOrNull()
                    val bw = text(CliFormFields.RADIO_BW).toDoubleOrNull()
                    val sf = text(CliFormFields.RADIO_SF).toIntOrNull()
                    val cr = text(CliFormFields.RADIO_CR).toIntOrNull()
                    if (f != null && f in 300.0..2500.0 && bw != null && bw in 7.0..500.0 &&
                        sf != null && sf in 5..12 && cr != null && cr in 5..8
                    ) {
                        out += "set radio ${CliReplies.RadioCsv(f, bw, sf, cr).toCsv()}"
                        reboot = "The radio settings you saved"
                    }
                }
                if (edited(CliIds.TX)) CliValues.parseInt(text(CliIds.TX), Int.MIN_VALUE..Int.MAX_VALUE)?.let { out += "set tx $it" }
            }
            POSITION -> {
                if (edited(CliIds.LAT)) text(CliIds.LAT).toDoubleOrNull()?.takeIf { it in -90.0..90.0 }?.let { out += "set lat $it" }
                if (edited(CliIds.LON)) text(CliIds.LON).toDoubleOrNull()?.takeIf { it in -180.0..180.0 }?.let { out += "set lon $it" }
            }
            ADVERTS -> {
                if (edited(CliIds.ADVERT_INTERVAL)) CliValues.parseInt(text(CliIds.ADVERT_INTERVAL), 0..Int.MAX_VALUE)?.let { out += "set advert.interval $it" }
                if (edited(CliIds.FLOOD_ADVERT_INTERVAL)) CliValues.parseInt(text(CliIds.FLOOD_ADVERT_INTERVAL), 0..Int.MAX_VALUE)?.let { out += "set flood.advert.interval $it" }
            }
            FORWARDING -> for (id in listOf(CliIds.FLOOD_MAX, CliIds.RXDELAY, CliIds.TXDELAY, CliIds.DIRECT_TXDELAY)) {
                if (edited(id)) CliValues.parseInt(text(id), 0..Int.MAX_VALUE)?.let { out += "set $id $it" }
            }
            SECURITY -> {
                // `password` is the admin password's own command, not `set password`.
                text(CliFormFields.PASSWORD_NEW).takeIf { edited(CliFormFields.PASSWORD_NEW) && it.isNotEmpty() && ' ' !in it }
                    ?.let { out += "password $it" }
                text(CliFormFields.GUEST_PASSWORD_NEW).takeIf { edited(CliFormFields.GUEST_PASSWORD_NEW) && ' ' !in it }
                    ?.let { out += "set guest.password $it" }
            }
            ADVANCED -> {
                if (edited(CliIds.DUTYCYCLE)) CliValues.parsePercent(text(CliIds.DUTYCYCLE), 1..100)?.let { out += "set dutycycle $it" }
                if (edited(CliIds.INT_THRESH)) CliValues.parseInt(text(CliIds.INT_THRESH), Int.MIN_VALUE..Int.MAX_VALUE)?.let { out += "set int.thresh $it" }
            }
        }
        return Saves(out, reboot)
    }

    /** After a fully successful save: nothing in [section] is edited any more; secrets are forgotten. */
    fun saved(section: Section) {
        for (f in section.fields) {
            dirty -= f.id
            if (f.kind == Kind.Secret) values.remove(f.id)
        }
    }

    companion object {
        const val BASIC = "basic"
        const val RADIO = "radio"
        const val POSITION = "position"
        const val ADVERTS = "adverts"
        const val FORWARDING = "forwarding"
        const val SECURITY = "security"
        const val ADVANCED = "advanced"

        val LOOP_MODES: List<String> = CliValues.LOOP_DETECT_MODES

        fun sections(role: NodeRole): List<Section> = buildList {
            add(Section(BASIC, "Basic", listOf(CliIds.NAME, CliIds.OWNER_INFO),
                listOf(Field(CliIds.NAME, "Name", Kind.Text), Field(CliIds.OWNER_INFO, "Owner info", Kind.MultiLine)),
                emptyList(), null))
            add(Section(RADIO, "Radio", listOf(CliIds.RADIO, CliIds.TX, CliIds.PATH_HASH_MODE),
                listOf(
                    Field(CliFormFields.RADIO_FREQ, "Freq (MHz)", Kind.Decimal),
                    Field(CliFormFields.RADIO_BW, "BW (kHz)", Kind.Decimal),
                    Field(CliFormFields.RADIO_SF, "SF", Kind.Integer),
                    Field(CliFormFields.RADIO_CR, "CR", Kind.Integer),
                    Field(CliIds.TX, "TX (dBm)", Kind.Integer),
                ),
                emptyList(), "Parameters must match your mesh or the node goes deaf."))
            add(Section(POSITION, "Position", listOf(CliIds.LAT, CliIds.LON),
                listOf(Field(CliIds.LAT, "Latitude", Kind.Decimal), Field(CliIds.LON, "Longitude", Kind.Decimal)),
                emptyList(), null))
            add(Section(ADVERTS, "Adverts", listOf(CliIds.ADVERT_INTERVAL, CliIds.FLOOD_ADVERT_INTERVAL),
                listOf(
                    Field(CliIds.ADVERT_INTERVAL, "Zero-hop (mins)", Kind.Integer),
                    Field(CliIds.FLOOD_ADVERT_INTERVAL, "Flood (hours)", Kind.Integer),
                ),
                emptyList(), null))
            if (role != NodeRole.Companion) {
                add(Section(FORWARDING, "Packet forwarding",
                    listOf(CliIds.REPEAT, CliIds.FLOOD_MAX, CliIds.RXDELAY, CliIds.TXDELAY, CliIds.DIRECT_TXDELAY),
                    listOf(
                        Field(CliIds.FLOOD_MAX, "Flood max hops", Kind.Integer),
                        Field(CliIds.RXDELAY, "RX delay", Kind.Integer),
                        Field(CliIds.TXDELAY, "TX delay", Kind.Integer),
                        Field(CliIds.DIRECT_TXDELAY, "Direct TX delay", Kind.Integer),
                    ),
                    listOf(Field(CliIds.REPEAT, "Repeat (forward packets)", Kind.Text)), null))
            }
            add(Section(SECURITY, "Access & security",
                if (role == NodeRole.Room) listOf(CliIds.ALLOW_READ_ONLY) else emptyList(),
                listOf(
                    Field(CliFormFields.PASSWORD_NEW, "New admin password", Kind.Secret),
                    Field(CliFormFields.GUEST_PASSWORD_NEW, "New guest password", Kind.Secret),
                ),
                if (role == NodeRole.Room) listOf(Field(CliIds.ALLOW_READ_ONLY, "Allow read-only (guest) logins", Kind.Text)) else emptyList(),
                "Passwords are never read back from the node, cross the mesh as plain CLI text, and stay out of the log."))
            add(Section(ADVANCED, "Advanced",
                listOf(CliIds.MULTI_ACKS, CliIds.RADIO_RXGAIN, CliIds.DUTYCYCLE, CliIds.INT_THRESH, CliIds.LOOP_DETECT),
                listOf(Field(CliIds.DUTYCYCLE, "Duty cycle %", Kind.Integer), Field(CliIds.INT_THRESH, "Int. thresh", Kind.Integer)),
                listOf(Field(CliIds.MULTI_ACKS, "Multi-acks (redundant ACKs)", Kind.Text), Field(CliIds.RADIO_RXGAIN, "RX gain boost (LNA)", Kind.Text)),
                null))
        }

        /** One-tap read-only commands whose reply is shown inline. */
        val MAINTENANCE_READS: List<String> = listOf("clock", "ver", "board", "neighbors", "log start", "log stop", "clock sync")

        /**
         * Commands that change or erase something — confirmed first.
         * `start ota` is left out (firmware updates are not on iOS), and
         * `get acl` too: it answers only the serial console, so over the
         * air it can only ever reply "??" — the access list is the binary
         * request on the Status screen.
         */
        val MAINTENANCE_DESTRUCTIVE: List<String> = listOf("reboot", "clear stats", "log erase", "erase")

        fun confirmText(command: String): String = "This action is destructive or hard to undo. Send \"$command\"?"

        fun saveResult(sent: Int, ok: Int): String = when {
            sent == 0 -> "Nothing valid to save"
            ok == sent -> "Saved"
            else -> "Saved $ok/$sent — check Console"
        }

        fun rebootNote(node: String, what: String): String =
            "$what is saved on $node, but the node is still running on its old radio settings. It applies them when it restarts."

        const val REBOOT_WARNING =
            "After the reboot this radio must be on the same settings to reach the node again. If they do not match, you will need physical access to the node."
    }
}
