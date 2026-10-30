package dev.sumin.skeleton.auth.mfa

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP — **SHA1 · 6자리 · 30초 · 비밀 160비트 · 앞뒤 한 걸음(±1)** 으로 고정이다(설정으로 열지 않는다: 인증 앱은 이 값 말고는 무시한다).
 * 걸음 = `floor(unix초 / 30)`. 같은 걸음의 코드를 두 번 받지 않는 일(RFC 6238 §5.2)은 저장소의 `last_used_step` 이 맡는다.
 */
object Totp {
    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L
    const val SECRET_BYTES = 20
    private const val SKEW = 1L
    private val random = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun stepOf(at: Instant): Long = Math.floorDiv(at.epochSecond, PERIOD_SECONDS)

    /** [step] 의 6자리 코드 (RFC 4226 동적 절단) */
    fun code(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** [code] 가 [now] 의 앞뒤 한 걸음 안의 코드면 그 걸음, 아니면 null. 비교는 상수 시간이고 세 걸음을 모두 계산한다 */
    fun match(secret: ByteArray, code: String, now: Instant): Long? {
        if (code.length != DIGITS || !code.all { it in '0'..'9' }) return null
        val current = stepOf(now)
        var found: Long? = null
        for (step in current - SKEW..current + SKEW) {
            if (MessageDigest.isEqual(code(secret, step).toByteArray(), code.toByteArray())) found = step
        }
        return found
    }
}
