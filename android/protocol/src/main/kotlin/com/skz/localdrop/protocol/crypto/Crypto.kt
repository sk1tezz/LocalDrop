package com.skz.localdrop.protocol.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Primitives matching desktop/localdrop/net (python `cryptography`). */
object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, n: Int): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(secret, salt, info))
        return ByteArray(n).also { g.generateBytes(it, 0, n) }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    fun verify(pub: ByteArray, sig: ByteArray, data: ByteArray): Boolean = try {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(pub, 0))
        v.update(data, 0, data.size)
        v.verifySignature(sig)
    } catch (_: Exception) {
        false
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Device id = sha256(public key)[:16] as hex, like identity.id_from_pub. */
fun idFromPub(pub: ByteArray): String = Crypto.sha256(pub).copyOf(16).hex()

/** Long-term Ed25519 key; [seed] (32 bytes) is what gets stored. */
class Identity(val seed: ByteArray) {
    private val key = Ed25519PrivateKeyParameters(seed, 0)
    val pub: ByteArray = key.generatePublicKey().encoded
    val id: String = idFromPub(pub)

    fun sign(data: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, key)
        s.update(data, 0, data.size)
        return s.generateSignature()
    }

    companion object {
        fun generate() = Identity(Crypto.randomBytes(32))
    }
}

/** Ephemeral X25519 key for one handshake. */
class Ephemeral {
    private val key = X25519PrivateKeyParameters(Crypto.random)
    val pub: ByteArray = key.generatePublicKey().encoded

    fun exchange(peer: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(key)
        return ByteArray(a.agreementSize).also { a.calculateAgreement(X25519PublicKeyParameters(peer, 0), it, 0) }
    }
}
