package dev.sumin.skeleton.crypto

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AesGcmTextEncryptor(
    private val primaryKeyId: String,
    private val keys: Map<String, SecretKey>,
    private val secureRandom: SecureRandom = SecureRandom(),
) : TextEncryptor {
    init {
        require(primaryKeyId.isNotBlank()) { "Primary crypto key id must not be blank." }
        require(primaryKeyId in keys) { "Primary crypto key '$primaryKeyId' is not configured." }
        require(keys.isNotEmpty()) { "At least one crypto key must be configured." }
    }

    override fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipherText = cipher(Cipher.ENCRYPT_MODE, requireNotNull(keys[primaryKeyId]), iv)
            .doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            ALGORITHM,
            VERSION,
            primaryKeyId,
            base64Url(iv + cipherText),
        ).joinToString(":")
    }

    override fun decrypt(cipherText: String): String {
        val envelope = parseEnvelope(cipherText)
        val payload = runCatching { Base64.getUrlDecoder().decode(envelope.payload) }
            .getOrElse { throw CryptoException("Encrypted text payload is not valid base64url.", it) }
        if (payload.size <= IV_BYTES) {
            throw CryptoException("Encrypted text payload is too short.")
        }

        val iv = payload.copyOfRange(0, IV_BYTES)
        val encrypted = payload.copyOfRange(IV_BYTES, payload.size)
        val key = keys[envelope.keyId] ?: throw CryptoException("Crypto key '${envelope.keyId}' is not configured.")
        val plain = runCatching {
            cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(encrypted)
        }.getOrElse { throw CryptoException("Encrypted text cannot be decrypted.", it) }
        return plain.toString(StandardCharsets.UTF_8)
    }

    private fun parseEnvelope(value: String): Envelope {
        val parts = value.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != ALGORITHM || parts[1] != VERSION || parts[2].isBlank() || parts[3].isBlank()) {
            throw CryptoException("Encrypted text must use envelope '$ALGORITHM:$VERSION:<keyId>:<payload>'.")
        }
        return Envelope(keyId = parts[2], payload = parts[3])
    }

    private fun cipher(
        mode: Int,
        key: SecretKey,
        iv: ByteArray,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").also { cipher ->
            cipher.init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private data class Envelope(
        val keyId: String,
        val payload: String,
    )

    companion object {
        private const val ALGORITHM = "aes-gcm"
        private const val VERSION = "v1"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
