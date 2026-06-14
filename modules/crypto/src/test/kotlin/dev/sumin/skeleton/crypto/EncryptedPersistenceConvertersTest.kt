package dev.sumin.skeleton.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EncryptedPersistenceConvertersTest {
    private val encryptor = AesGcmTextEncryptor(
        primaryKeyId = "local",
        keys = mapOf("local" to AesGcmKey.fromBase64(key(1))),
    )

    @Test
    fun `jpa converter encrypts database column and decrypts entity attribute`() {
        val converter = EncryptedStringJpaAttributeConverter(encryptor)

        val databaseValue = converter.convertToDatabaseColumn("db-secret")
        val entityValue = converter.convertToEntityAttribute(databaseValue)

        assertNotEquals("db-secret", databaseValue)
        assertEquals("db-secret", entityValue)
    }

    @Test
    fun `jdbc converters encrypt and decrypt encrypted string wrapper`() {
        val writer = EncryptedStringWritingConverter(encryptor)
        val reader = EncryptedStringReadingConverter(encryptor)

        val databaseValue = writer.convert(EncryptedString("jdbc-secret"))
        val restored = reader.convert(databaseValue)

        assertNotEquals("jdbc-secret", databaseValue)
        assertEquals(EncryptedString("jdbc-secret"), restored)
    }

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { index -> (seed + index).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
