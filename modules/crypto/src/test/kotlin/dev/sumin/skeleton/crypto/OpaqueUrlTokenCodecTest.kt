package dev.sumin.skeleton.crypto

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OpaqueUrlTokenCodecTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC)
    private val encryptor = AesGcmTextEncryptor(
        primaryKeyId = "local",
        keys = mapOf("local" to AesGcmKey.fromBase64(key(1))),
    )
    private val codec = OpaqueUrlTokenCodec(encryptor, clock)

    @Test
    fun `encodes storage object key as opaque url safe token and decodes it`() {
        val token = codec.encode(
            OpaqueUrlTokenPayload(
                value = "contents/video intro.mp4",
                purpose = "storage-public-url",
                expiresAt = Instant.parse("2026-06-16T00:10:00Z"),
                metadata = mapOf("contentType" to "video/mp4"),
            ),
        )

        assertFalse(token.contains("contents"))
        assertFalse(token.contains("/"))
        assertFalse(token.contains("+"))
        assertFalse(token.contains("="))

        val decoded = codec.decode(token, expectedPurpose = "storage-public-url")

        assertEquals("contents/video intro.mp4", decoded.value)
        assertEquals("storage-public-url", decoded.purpose)
        assertEquals(Instant.parse("2026-06-16T00:10:00Z"), decoded.expiresAt)
        assertEquals("video/mp4", decoded.metadata["contentType"])
    }

    @Test
    fun `rejects token decoded for a different purpose`() {
        val token = codec.encode(
            OpaqueUrlTokenPayload(
                value = "/ko/content/video-1",
                purpose = "frontend-route",
            ),
        )

        assertFailsWith<CryptoException> {
            codec.decode(token, expectedPurpose = "storage-public-url")
        }
    }

    @Test
    fun `rejects expired token`() {
        val token = codec.encode(
            OpaqueUrlTokenPayload(
                value = "contents/image.png",
                purpose = "storage-public-url",
                expiresAt = Instant.parse("2026-06-15T23:59:59Z"),
            ),
        )

        assertFailsWith<CryptoException> {
            codec.decode(token, expectedPurpose = "storage-public-url")
        }
    }

    @Test
    fun `rejects tampered token`() {
        val token = codec.encode(
            OpaqueUrlTokenPayload(
                value = "contents/image.png",
                purpose = "storage-public-url",
            ),
        )
        val tampered = token.dropLast(1) + if (token.last() == 'A') "B" else "A"

        assertFailsWith<CryptoException> {
            codec.decode(tampered, expectedPurpose = "storage-public-url")
        }
    }

    @Test
    fun `rejects token that is not base64url`() {
        assertFailsWith<CryptoException> {
            codec.decode("not/a/token", expectedPurpose = "storage-public-url")
        }
    }

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { index -> (seed + index).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
