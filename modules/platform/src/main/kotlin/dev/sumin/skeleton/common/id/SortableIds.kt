package dev.sumin.skeleton.common.id

import java.security.SecureRandom
import java.math.BigInteger
import java.time.Clock
import java.util.UUID
import java.util.random.RandomGenerator

/**
 * 행 id 를 만드는 한 곳 — `접두사 + ULID`(26자, 소문자 Crockford base32).
 * 앞 48비트 = 만든 시각(ms), 뒤 80비트 = 난수. 문자열 정렬이 곧 만든 순서다.
 *
 * - 같은 ms 안에서도 늘어난다(난수부를 1씩 올림). 시계가 뒤로 가도 앞서 만든 id 보다 작아지지 않는다.
 * - 시각이 드러나지만 80비트 난수라 id 를 추측할 수 없다. 비밀 값(토큰 · 핸들)에는 쓰지 않는다.
 * - 인스턴스 안에서만 단조 증가한다 — 기본 [system] 하나를 같이 쓴다.
 */
class SortableIds(
    private val clock: Clock = Clock.systemUTC(),
    private val random: RandomGenerator = SecureRandom(),
) {
    private var lastMillis = -1L
    private val counter = ByteArray(RANDOM_BYTES)
    private var uuidMillis = -1L
    private var uuidCounter = BigInteger.ZERO

    @Synchronized
    fun newId(prefix: String = ""): String {
        var millis = maxOf(clock.millis(), lastMillis)
        if (millis == lastMillis && !increment()) millis += 1
        if (millis != lastMillis) random.nextBytes(counter)
        lastMillis = millis
        return prefix + encode(millis)
    }

    /**
     * 시간순 UUID(v7, RFC 9562) — 열 타입이 `uuid` 인 표용. 같은 ms 안에서는 74비트 난수부를 1씩 올려 [newId] 처럼 늘어난다.
     * 문자열(소문자 16진수)로 정렬해도 만든 순서다.
     */
    @Synchronized
    fun newUuid(): UUID {
        var millis = maxOf(clock.millis(), uuidMillis)
        if (millis == uuidMillis) {
            uuidCounter = uuidCounter.add(BigInteger.ONE)
            if (uuidCounter.bitLength() > UUID_RANDOM_BITS) millis += 1
        }
        if (millis != uuidMillis) uuidCounter = BigInteger(UUID_RANDOM_BITS, secureOrGiven())
        uuidMillis = millis
        val randA = uuidCounter.shiftRight(62).toLong()
        val randB = uuidCounter.toLong() and ((1L shl 62) - 1)
        return UUID((millis shl 16) or (7L shl 12) or randA, (2L shl 62) or randB)
    }

    private fun secureOrGiven(): java.util.Random = object : java.util.Random() {
        override fun next(bits: Int): Int = random.nextInt() ushr (32 - bits)
    }

    /** 난수부를 1 올린다. 넘치면 false */
    private fun increment(): Boolean {
        for (i in counter.indices.reversed()) {
            counter[i]++
            if (counter[i] != 0.toByte()) return true
        }
        return false
    }

    private fun encode(millis: Long): String {
        val bytes = ByteArray(TIME_BYTES + RANDOM_BYTES)
        for (i in 0 until TIME_BYTES) bytes[i] = (millis ushr (8 * (TIME_BYTES - 1 - i))).toByte()
        counter.copyInto(bytes, TIME_BYTES)
        val out = CharArray(LENGTH)
        for (c in 0 until LENGTH) {
            var v = 0
            for (b in 0 until 5) {
                val bit = c * 5 + b - PAD_BITS   // 128비트 값을 130비트로 보고 앞에 0 두 개
                v = v shl 1
                if (bit >= 0) v = v or ((bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1)
            }
            out[c] = ALPHABET[v]
        }
        return String(out)
    }

    companion object {
        const val LENGTH = 26
        private const val TIME_BYTES = 6
        private const val RANDOM_BYTES = 10
        private const val PAD_BITS = 2
        private const val UUID_RANDOM_BITS = 74
        private val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray()

        /** 앱이 같이 쓰는 기본 생성기 */
        @JvmStatic
        val system: SortableIds by lazy { SortableIds() }
    }
}
