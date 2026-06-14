package dev.sumin.skeleton.crypto

import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object AesGcmKey {
    fun fromBase64(value: String): SecretKey {
        val bytes = runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { throw CryptoException("AES-GCM key must be base64 encoded.", it) }
        require(bytes.size == 16 || bytes.size == 24 || bytes.size == 32) {
            "AES-GCM key must decode to 16, 24, or 32 bytes."
        }
        return SecretKeySpec(bytes, "AES")
    }
}
