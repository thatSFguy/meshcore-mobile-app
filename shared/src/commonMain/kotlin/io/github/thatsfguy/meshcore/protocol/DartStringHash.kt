package io.github.thatsfguy.meshcore.protocol

/**
 * Dart's `String.hashCode`, reimplemented.
 *
 * This exists for exactly one reason: MeshCore reactions ([Reactions])
 * identify their target by `input.hashCode & 0xFFFF`, computed by a
 * Flutter client. To put a reaction on the right message in that client
 * we have to produce the same number, which means mirroring another
 * runtime's hash function rather than choosing a good one.
 *
 * The algorithm is Jenkins one-at-a-time over UTF-16 **code units**, in
 * 32-bit unsigned arithmetic — the Dart VM's `CombineHashes` /
 * `FinalizeHash` pair (`runtime/vm/hash.h`). Kotlin strings are UTF-16
 * too, so iterating `Char`s matches what the VM hashes for both its
 * one-byte (Latin-1) and two-byte string representations.
 *
 * **Known limits — read before trusting a match:**
 *
 *  - Dart's string hash is an *implementation detail*, not a specified
 *    function. The VM (what Flutter runs on a phone) and dart2js use
 *    different variants; this mirrors the VM.
 *  - `FinalizeHash` truncates to the VM's hash-bit width and maps 0 to
 *    1. Since callers keep only the low 16 bits, the truncation is
 *    invisible for any width >= 16 — but the zero-to-one substitution
 *    could differ in the one-in-a-billion case where the truncated hash
 *    is exactly 0. Not worth carrying the ambiguity further.
 *  - This has been checked against the reference implementation's source,
 *    NOT against values it produced at runtime — the reference client's
 *    own tests only assert that the hash is deterministic and four hex
 *    digits, never what it equals. Treat cross-client attachment as
 *    unconfirmed until it is seen working against a real peer.
 */
object DartStringHash {

    /** Full 32-bit hash; callers mask to the width they need. */
    fun of(text: String): Int {
        var hash = 0
        for (c in text) hash = combine(hash, c.code)
        return finalize(hash)
    }

    private fun combine(hash: Int, value: Int): Int {
        var h = hash + value
        h += h shl 10
        h = h xor (h ushr 6)     // logical shift: the hash is unsigned
        return h
    }

    private fun finalize(hash: Int): Int {
        var h = hash
        h += h shl 3
        h = h xor (h ushr 11)
        h += h shl 15
        return if (h == 0) 1 else h
    }
}
