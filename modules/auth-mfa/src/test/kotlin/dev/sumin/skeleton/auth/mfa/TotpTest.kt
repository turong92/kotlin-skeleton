package dev.sumin.skeleton.auth.mfa

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RFC 6238 부록 B(SHA1) 시험 벡터 · ±1 걸음만 받는 창 · 비밀의 길이 */
class TotpTest {
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    /** 부록 B 의 8자리 값 뒤 6자리 = 6자리 TOTP (둘 다 같은 동적 절단 값의 10^k 나머지) */
    private val vectors = mapOf(
        59L to "287082", 1111111109L to "081804", 1111111111L to "050471",
        1234567890L to "005924", 2000000000L to "279037", 20000000000L to "353130",
    )

    @Test
    fun `RFC 6238 appendix B vectors`() {
        vectors.forEach { (seconds, expected) ->
            assertEquals(expected, Totp.code(rfcSecret, Totp.stepOf(Instant.ofEpochSecond(seconds))), "T=$seconds")
        }
    }

    @Test
    fun `a code is accepted from one step back through one step ahead and nothing beyond`() {
        val now = Instant.ofEpochSecond(1_700_000_010)
        val step = Totp.stepOf(now)
        for (delta in -1L..1L) assertEquals(step + delta, Totp.match(rfcSecret, Totp.code(rfcSecret, step + delta), now), "delta $delta")
        assertNull(Totp.match(rfcSecret, Totp.code(rfcSecret, step - 2), now))
        assertNull(Totp.match(rfcSecret, Totp.code(rfcSecret, step + 2), now))
    }

    @Test
    fun `junk is not a code`() {
        val now = Instant.ofEpochSecond(1_700_000_010)
        listOf("", "12345", "1234567", "abcdef", "12 456").forEach { assertNull(Totp.match(rfcSecret, it, now), it) }
    }

    @Test
    fun `a new secret is 160 bits and never repeats`() {
        val a = Totp.newSecret()
        assertEquals(20, a.size)
        assertEquals(false, a.contentEquals(Totp.newSecret()))
    }

    @Test
    fun `base32 round-trips and tolerates case, spaces and padding`() {
        listOf(ByteArray(0), byteArrayOf(0), byteArrayOf(-1, 0, 1), rfcSecret, Totp.newSecret()).forEach {
            assertContentEquals(it, Base32.decode(Base32.encode(it)))
        }
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(rfcSecret))   // RFC 6238 비밀의 잘 알려진 표기
        assertContentEquals(rfcSecret, Base32.decode("gezd gnbv-gy3t qojq GEZDGNBVGY3TQOJQ=="))
        assertNull(Base32.decodeOrNull("not*base32"))
    }
}
