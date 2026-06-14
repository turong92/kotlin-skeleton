package dev.sumin.skeleton.crypto

import java.util.Base64
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CryptoAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration::class.java))

    @Test
    fun `auto configuration stays passive when no keys are configured`() {
        contextRunner.run { context ->
            assertFalse(context.containsBean("textEncryptor"))
        }
    }

    @Test
    fun `auto configuration creates text encryptor from configured primary key`() {
        contextRunner
            .withPropertyValues(
                "skeleton.crypto.primary-key-id=local",
                "skeleton.crypto.keys.local=${key(1)}",
            )
            .run { context ->
                val encryptor = context.getBean(TextEncryptor::class.java)
                val cipherText = encryptor.encrypt("secret")

                assertEquals("secret", encryptor.decrypt(cipherText))
                assertTrue(context.containsBean("encryptedStringWritingConverter"))
                assertTrue(context.containsBean("encryptedStringReadingConverter"))
            }
    }

    @Test
    fun `auto configuration ignores blank optional keys`() {
        contextRunner
            .withPropertyValues(
                "skeleton.crypto.primary-key-id=local",
                "skeleton.crypto.keys.local=${key(1)}",
                "skeleton.crypto.keys.previous=",
            )
            .run { context ->
                val encryptor = context.getBean(TextEncryptor::class.java)
                val cipherText = encryptor.encrypt("secret")

                assertEquals("secret", encryptor.decrypt(cipherText))
            }
    }

    @Test
    fun `custom text encryptor overrides default`() {
        val custom = object : TextEncryptor {
            override fun encrypt(plainText: String): String = "custom:$plainText"
            override fun decrypt(cipherText: String): String = cipherText.removePrefix("custom:")
        }

        contextRunner
            .withPropertyValues(
                "skeleton.crypto.primary-key-id=local",
                "skeleton.crypto.keys.local=${key(1)}",
            )
            .withBean(TextEncryptor::class.java, Supplier { custom })
            .run { context ->
                assertEquals("custom:secret", context.getBean(TextEncryptor::class.java).encrypt("secret"))
            }
    }

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { index -> (seed + index).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
