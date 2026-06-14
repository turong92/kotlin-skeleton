package dev.sumin.skeleton.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmTextEncryptorTest {
    @Test
    fun `encrypts text with aes gcm envelope and decrypts it`() {
        val encryptor = AesGcmTextEncryptor(
            primaryKeyId = "local",
            keys = mapOf("local" to AesGcmKey.fromBase64(key(1))),
        )

        val first = encryptor.encrypt("secret-payment-key")
        val second = encryptor.encrypt("secret-payment-key")

        assertTrue(first.startsWith("aes-gcm:v1:local:"))
        assertNotEquals("secret-payment-key", first)
        assertNotEquals(first, second)
        assertEquals("secret-payment-key", encryptor.decrypt(first))
        assertEquals("secret-payment-key", encryptor.decrypt(second))
    }

    @Test
    fun `decrypts old key id while encrypting with primary key`() {
        val oldEncryptor = AesGcmTextEncryptor(
            primaryKeyId = "old",
            keys = mapOf("old" to AesGcmKey.fromBase64(key(1))),
        )
        val newEncryptor = AesGcmTextEncryptor(
            primaryKeyId = "new",
            keys = mapOf(
                "old" to AesGcmKey.fromBase64(key(1)),
                "new" to AesGcmKey.fromBase64(key(2)),
            ),
        )

        val oldCipherText = oldEncryptor.encrypt("rotatable-secret")
        val newCipherText = newEncryptor.encrypt("rotatable-secret")

        assertTrue(newCipherText.startsWith("aes-gcm:v1:new:"))
        assertEquals("rotatable-secret", newEncryptor.decrypt(oldCipherText))
        assertEquals("rotatable-secret", newEncryptor.decrypt(newCipherText))
    }

    @Test
    fun `rejects invalid envelope and unknown key id`() {
        val encryptor = AesGcmTextEncryptor(
            primaryKeyId = "local",
            keys = mapOf("local" to AesGcmKey.fromBase64(key(1))),
        )

        assertFailsWith<CryptoException> {
            encryptor.decrypt("legacy-xor-value")
        }
        assertFailsWith<CryptoException> {
            encryptor.decrypt("aes-gcm:v1:missing:abc")
        }
    }

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { index -> (seed + index).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
