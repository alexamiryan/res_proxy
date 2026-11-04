package dev.resproxy.net

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/** Curve25519 (RFC 7748) for WireGuard key pairs; only runs a few times, so BigInteger is fine. */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    /** (privateKey, publicKey), base64 as in wg-quick configs. */
    fun newKeyPair(): Pair<String, String> {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it); clamp(it) }
        val b64 = Base64.getEncoder()
        return b64.encodeToString(priv) to b64.encodeToString(publicKey(priv))
    }

    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, BASE)

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val kc = k.copyOf().also(::clamp)
        val n = le(kc)
        val uc = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = le(uc).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = n.testBit(t)
            if (swap != bit) { x2 = x3.also { x3 = x2 }; z2 = z3.also { z3 = z2 } }
            swap = bit
            val a = x2.add(z2); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb)
            val c = x3.add(z3); val d = x3.subtract(z3)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap) { x2 = x3; z2 = z3 }
        return toLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private fun clamp(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
    }

    private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun toLe(v: BigInteger): ByteArray {
        val be = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return (ByteArray(32 - be.size) + be).reversedArray()
    }
}
