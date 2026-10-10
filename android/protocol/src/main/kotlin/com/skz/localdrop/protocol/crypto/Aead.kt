package com.skz.localdrop.protocol.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a caller-built 12-byte nonce; tag appended (python AESGCM layout). */
class Aead(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")

    @Synchronized
    fun seal(nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null, off: Int = 0, len: Int = plain.size): ByteArray {
        enc.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
        if (aad != null) enc.updateAAD(aad)
        return enc.doFinal(plain, off, len)
    }

    /** Throws on a bad tag. */
    @Synchronized
    fun open(nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null, off: Int = 0, len: Int = sealed.size): ByteArray {
        dec.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce))
        if (aad != null) dec.updateAAD(aad)
        return dec.doFinal(sealed, off, len)
    }
}
