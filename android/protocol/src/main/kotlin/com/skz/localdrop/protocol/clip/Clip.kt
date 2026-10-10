package com.skz.localdrop.protocol.clip

import com.skz.localdrop.protocol.crypto.Crypto
import com.skz.localdrop.protocol.crypto.hex

/**
 * Clipboard message and hashes of desktop/localdrop/clip/sync.py:
 *     clip {ctype: text | image, text?, w?, h?, hash, manual?}   body = PNG for images
 * A hash of what we wrote ourselves stops clips from echoing back and forth.
 */
const val MAX_TEXT = 1_000_000
const val MAX_IMAGE = 25 * 1024 * 1024
const val MAX_PIXELS = 80_000_000L

/** "t" + sha256(utf-8). Kotlin strings are UTF-16 like python's surrogatepass input, so lone surrogates stay rare edge cases. */
fun textHash(text: String): String = "t" + Crypto.sha256(text.toByteArray(Charsets.UTF_8)).hex()

/** "i" + sha256("(w, h)" + RGB bytes row by row): PIL's f"{img.size}" + img.convert("RGB").tobytes(). */
fun imageHash(w: Int, h: Int, rgb: ByteArray): String =
    "i" + Crypto.sha256("($w, $h)".toByteArray(), rgb).hex()

/** RGB bytes from ARGB ints (Android Bitmap.getPixels, alpha dropped like PIL's convert("RGB")). */
fun argbToRgb(px: IntArray): ByteArray {
    val out = ByteArray(px.size * 3)
    var j = 0
    for (p in px) {
        out[j++] = (p shr 16).toByte()
        out[j++] = (p shr 8).toByte()
        out[j++] = p.toByte()
    }
    return out
}
