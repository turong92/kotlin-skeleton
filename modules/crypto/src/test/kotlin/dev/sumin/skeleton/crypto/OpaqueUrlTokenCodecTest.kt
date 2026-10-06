package dev.sumin.skeleton.crypto

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // 토큰 = base64url( "aes-gcm:v1:<keyId>:" + base64url(iv(12) + 암호문 + GCM 태그(16)) ).
    // 마지막 글자만 바꾸는 변조는 base64 의 버려지는 하위 비트만 건드려 바이트가 그대로일 수 있다 — 변조 시험은 실제 바이트를 뒤집는다.
    private fun token(valueLength: Int = 18) =
        codec.encode(OpaqueUrlTokenPayload(value = "k".repeat(valueLength), purpose = "storage-public-url"))

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    /** 안쪽 페이로드(iv + 암호문 + 태그) 바이트 하나를 뒤집고, 두 겹 base64url 을 정규형으로 다시 감싼다. */
    private fun flipInnerByte(token: String, index: (Int) -> Int): String {
        val envelope = String(urlDecoder.decode(token), Charsets.UTF_8).split(":", limit = 4)
        val inner = urlDecoder.decode(envelope[3])
        inner[index(inner.size)] = (inner[index(inner.size)].toInt() xor 0x01).toByte()
        val rebuilt = envelope.take(3).joinToString(":") + ":" + urlEncoder.encodeToString(inner)
        return urlEncoder.encodeToString(rebuilt.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `rejects token whose signature region byte was flipped`() {
        val last = flipInnerByte(token()) { it - 1 }          // GCM 태그의 마지막 바이트
        val firstOfTag = flipInnerByte(token()) { it - 16 }   // GCM 태그의 첫 바이트

        assertFailsWith<CryptoException> { codec.decode(last, expectedPurpose = "storage-public-url") }
        assertFailsWith<CryptoException> { codec.decode(firstOfTag, expectedPurpose = "storage-public-url") }
    }

    @Test
    fun `rejects token whose payload region byte was flipped`() {
        val firstCipherByte = flipInnerByte(token()) { 12 }   // iv 바로 뒤 = 암호문 첫 바이트
        val iv = flipInnerByte(token()) { 0 }                 // iv 의 첫 바이트

        assertFailsWith<CryptoException> { codec.decode(firstCipherByte, expectedPurpose = "storage-public-url") }
        assertFailsWith<CryptoException> { codec.decode(iv, expectedPurpose = "storage-public-url") }
    }

    @Test
    fun `rejects token whose key id was rewritten`() {
        val envelope = String(urlDecoder.decode(token()), Charsets.UTF_8)
        val rewritten = urlEncoder.encodeToString(envelope.replace(":local:", ":other:").toByteArray(Charsets.UTF_8))

        assertFailsWith<CryptoException> { codec.decode(rewritten, expectedPurpose = "storage-public-url") }
    }

    /** 마지막 글자를 같은 바이트로 디코드되는 다른 글자로 바꾼 변형들(JDK 디코더는 버려지는 비트를 검사하지 않는다) */
    private fun sameBytesVariants(token: String): List<String> {
        val bytes = urlDecoder.decode(token)
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9') + '-' + '_'
        return alphabet.map { token.dropLast(1) + it }.filter { it != token && urlDecoder.decode(it).contentEquals(bytes) }
    }

    @Test
    fun `a final character that decodes to the same bytes is rejected as non canonical`() {
        var checked = 0
        // 값 길이를 바꿔 가며 바깥 base64 가 버리는 비트 수(0 · 2 · 4)를 모두 지난다
        (1..12).forEach { n ->
            val original = token(n)
            sameBytesVariants(original).forEach { variant ->
                checked++
                assertFailsWith<CryptoException>("non-canonical variant of a length-$n token") {
                    codec.decode(variant, expectedPurpose = "storage-public-url")
                }
            }
        }
        assertTrue(checked > 0, "the loop must reach lengths whose last character has discarded bits")
    }

    @Test
    fun `an inner payload whose final character is non canonical is rejected`() {
        (1..12).forEach { n ->
            val envelope = String(urlDecoder.decode(token(n)), Charsets.UTF_8).split(":", limit = 4)
            val innerVariants = sameBytesVariants(envelope[3])
            innerVariants.forEach { inner ->
                val rebuilt = envelope.take(3).joinToString(":") + ":" + inner
                val outer = urlEncoder.encodeToString(rebuilt.toByteArray(Charsets.UTF_8))
                assertFailsWith<CryptoException> { codec.decode(outer, expectedPurpose = "storage-public-url") }
            }
        }
    }

    @Test
    fun `a padded token is not canonical`() {
        val padded = (1..12).map { token(it) }.first { it.length % 4 != 0 }
        val withPadding = padded + "=".repeat(4 - padded.length % 4)

        assertFailsWith<CryptoException> { codec.decode(withPadding, expectedPurpose = "storage-public-url") }
    }

    @Test
    fun `swapping only the last base64 character can leave the bytes identical`() {
        // 옛 시험이 ~10% 거짓 통과하던 이유를 결정적으로: "ab"(2바이트) → "YWI" 이고 "YWJ" 도 같은 2바이트다
        assertEquals("YWI", urlEncoder.encodeToString("ab".toByteArray()))
        assertTrue(urlDecoder.decode("YWI").contentEquals(urlDecoder.decode("YWJ")))
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
