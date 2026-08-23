package io.github.thatsfguy.meshcore.protocol

import java.math.BigInteger

/**
 * A textbook Ed25519 scalar multiplication of the base point, written
 * out here so the tests have something to check our key handling
 * *against* rather than checking it against itself.
 *
 * The reason this exists is LESSONS' recurring one: a builder tested
 * against our own parser is the same assumption written twice. Both
 * halves of MeshCore identity — `SHA512(seed)` clamped, and the public
 * key the node derives from the scalar half of that — are otherwise
 * only ever produced by our own code or by Bouncy Castle's seed-based
 * API, neither of which can tell us that the firmware agrees.
 *
 * This can. `LocalIdentity::validatePrivateKey` (`src/Identity.cpp:67-84`)
 * carries a hardcoded known-good keypair — 64 bytes of private key and
 * the 32-byte public key it must produce — and running that private key
 * through this function has to give that public key. That is a real
 * ground-truth vector out of the firmware, and it is what
 * `MeshIdentityTest.theFirmwaresOwnKeypairDerivesItsPublishedPublicKey`
 * pins.
 *
 * Slow, allocation-happy, and constant-time in no sense whatsoever.
 * Test code only — never let this near a signing path.
 */
object Ed25519Reference {

    private val Q: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(Q)).mod(Q)
    private val I: BigInteger = BigInteger.TWO.modPow((Q - BigInteger.ONE) / BigInteger.valueOf(4), Q)

    /** Extended coordinates (X, Y, Z, T). */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(Q)).mod(Q)
        val x = recoverX(y)
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(Q))
    }

    /**
     * The public key for a **clamped scalar** — the first 32 bytes of a
     * MeshCore `PRV_KEY_SIZE` private key, little-endian, exactly as
     * `ed25519_derive_pub` reads them (`src/Identity.cpp:69`).
     */
    fun publicKeyFromScalar(scalar32: ByteArray): ByteArray {
        require(scalar32.size == 32) { "scalar must be 32 bytes" }
        val e = BigInteger(1, scalar32.reversedArray())
        return encode(scalarMult(BASE, e))
    }

    private fun recoverX(y: BigInteger): BigInteger {
        val yy = y.multiply(y).mod(Q)
        val xx = (yy - BigInteger.ONE)
            .multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(Q)).mod(Q)
        var x = xx.modPow((Q + BigInteger.valueOf(3)) / BigInteger.valueOf(8), Q)
        if (x.multiply(x).subtract(xx).mod(Q) != BigInteger.ZERO) x = x.multiply(I).mod(Q)
        if (x.testBit(0)) x = Q - x
        return x
    }

    private fun add(p: Point, q: Point): Point {
        val a = (p.y - p.x).multiply(q.y - q.x).mod(Q)
        val b = (p.y + p.x).multiply(q.y + q.x).mod(Q)
        val c = p.t.multiply(BigInteger.TWO).multiply(D).multiply(q.t).mod(Q)
        val dd = p.z.multiply(BigInteger.TWO).multiply(q.z).mod(Q)
        val e = b - a
        val f = dd - c
        val g = dd + c
        val h = b + a
        return Point(e.multiply(f).mod(Q), g.multiply(h).mod(Q), f.multiply(g).mod(Q), e.multiply(h).mod(Q))
    }

    private fun scalarMult(point: Point, scalar: BigInteger): Point {
        var result = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var addend = point
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    /** Little-endian y with the sign of x in the top bit — RFC 8032 §5.1.2. */
    private fun encode(p: Point): ByteArray {
        val zi = p.z.modInverse(Q)
        val x = p.x.multiply(zi).mod(Q)
        val y = p.y.multiply(zi).mod(Q)
        val out = ByteArray(32)
        for (i in 0 until 32) {
            var b = 0
            for (bit in 0 until 8) {
                val index = i * 8 + bit
                if (index < 255 && y.testBit(index)) b = b or (1 shl bit)
            }
            out[i] = b.toByte()
        }
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }
}
