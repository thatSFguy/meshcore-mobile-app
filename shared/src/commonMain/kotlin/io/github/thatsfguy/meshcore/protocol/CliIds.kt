package io.github.thatsfguy.meshcore.protocol

/**
 * The CLI variable and action names, spelled once.
 *
 * These strings go over the air as `get <id>` / `set <id> <value>` and
 * decide what a repeater does. Two files used to hand-type all of them
 * — [CliCatalog], which documents them, and the remote settings form,
 * which sends them — with nothing making the two agree. A typo in
 * either is silent: the catalogue would describe a command that is
 * never sent, or the form would send one no node answers, and neither
 * shows up until someone's repeater is misconfigured.
 *
 * This is the same defect shape as the hop-hash width (LESSONS §7) and
 * as the four "second caller didn't get the fix" bugs found on
 * 2026-08-06: a value that must match in two places, matched by hand.
 *
 * Not to be confused with [CliFormFields], which look identical and
 * are never sent anywhere.
 */
object CliIds {
    const val ADVERT: String = "advert"
    const val ADVERT_INTERVAL: String = "advert.interval"
    const val ALLOW_READ_ONLY: String = "allow.read.only"
    const val BOARD: String = "board"
    const val CLOCK: String = "clock"
    const val DIRECT_TXDELAY: String = "direct.txdelay"
    const val DUTYCYCLE: String = "dutycycle"
    const val ERASE: String = "erase"
    const val FLOOD_ADVERT_INTERVAL: String = "flood.advert.interval"
    const val FLOOD_MAX: String = "flood.max"
    const val INT_THRESH: String = "int.thresh"
    const val LAT: String = "lat"
    const val LON: String = "lon"
    const val LOOP_DETECT: String = "loop.detect"
    const val MULTI_ACKS: String = "multi.acks"
    const val NAME: String = "name"
    const val NEIGHBORS: String = "neighbors"
    const val OWNER_INFO: String = "owner.info"
    const val PATH_HASH_MODE: String = "path.hash.mode"
    const val RADIO: String = "radio"
    const val RADIO_RXGAIN: String = "radio.rxgain"
    const val REBOOT: String = "reboot"
    const val REPEAT: String = "repeat"
    const val RXDELAY: String = "rxdelay"
    const val TX: String = "tx"
    const val TXDELAY: String = "txdelay"
    const val VER: String = "ver"

    /**
     * Every id above, so a test can check them against the catalogue
     * without reflection. A constant missing from here is caught by
     * CliIdsCoverageTest, which counts the declarations in this file.
     */
    val ALL: Set<String> = setOf(
        ADVERT,
        ADVERT_INTERVAL,
        ALLOW_READ_ONLY,
        BOARD,
        CLOCK,
        DIRECT_TXDELAY,
        DUTYCYCLE,
        ERASE,
        FLOOD_ADVERT_INTERVAL,
        FLOOD_MAX,
        INT_THRESH,
        LAT,
        LON,
        LOOP_DETECT,
        MULTI_ACKS,
        NAME,
        NEIGHBORS,
        OWNER_INFO,
        PATH_HASH_MODE,
        RADIO,
        RADIO_RXGAIN,
        REBOOT,
        REPEAT,
        RXDELAY,
        TX,
        TXDELAY,
        VER,
    )
}

/**
 * Keys the settings form uses internally that are NOT CLI ids.
 *
 * The radio has one `radio` command taking a CSV of frequency,
 * bandwidth, spreading factor and coding rate; the form edits those as
 * four separate fields and composes the CSV on save. Passwords are
 * write-only, so their fields are keyed separately from the commands
 * that set them.
 *
 * These are spelled like ids and are indistinguishable from them by
 * eye, which is exactly why they are named apart: the form builds
 * `set $id` from id lists in places, and one of these reaching that
 * path would send a command no node understands.
 */
object CliFormFields {
    const val RADIO_FREQ: String = "radio.freq"
    const val RADIO_BW: String = "radio.bw"
    const val RADIO_SF: String = "radio.sf"
    const val RADIO_CR: String = "radio.cr"
    const val PASSWORD_NEW: String = "password.new"
    const val GUEST_PASSWORD_NEW: String = "guest.password.new"

    /** The four fields that compose one `set radio <csv>`. */
    val RADIO_FIELDS: List<String> = listOf(RADIO_FREQ, RADIO_BW, RADIO_SF, RADIO_CR)

    /** Every synthetic key, for the test that keeps them out of the wire. */
    val ALL: Set<String> = setOf(
        RADIO_FREQ, RADIO_BW, RADIO_SF, RADIO_CR, PASSWORD_NEW, GUEST_PASSWORD_NEW,
    )
}
