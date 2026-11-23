package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.challenge.ChallengeRow
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** I2 — "재전송" 이 가입 여부를 드러내는 오라클이 아니다: 확인된 계정이 있는 주소의 시도도 없는 주소의 시도와 **똑같이** 변한다 */
class ResendOracleTest {
    private val h = AccountHarness()

    /** 관측할 수 있는 모든 것 — 남은 추측 · 재전송 횟수 · 만료까지 · 마지막 발송 이후 */
    private data class Observation(val wrongGuessAnswers: List<Any?>, val resends: Int, val expiresInSeconds: Long, val sinceSentSeconds: Long)

    private fun probe(email: String, ip: String): Observation {
        val attempt = h.signUp(email, ip = ip)
        val id = h.challenges.idOf(attempt.signUpId!!)!!
        val answers = mutableListOf<Any?>()
        fun guess(code: String) {
            answers += assertFailsWith<ApplicationException> { h.registration.verifyEmail(attempt.signUpId!!, code, null) }.data
        }
        guess("000000")                                   // attemptsLeft 4
        h.time.advance(Duration.ofSeconds(31))             // past the resend cooldown
        h.registration.resendVerification(attempt.signUpId!!, ip, null)
        guess("000001")                                   // 4 again if the resend reset the attempts, 3 if it did not
        val row: ChallengeRow = h.challengeStore.find(id)!!
        return Observation(answers, row.resends, Duration.between(h.time.now(), row.expiresAt).seconds, Duration.between(row.lastSentAt, h.time.now()).seconds)
    }

    @Test
    fun `the four-request probe answers identically for an address with a verified account and one without`() {
        h.activeAccount("taken@example.com")
        val taken = probe("taken@example.com", "198.51.100.1")
        val free = probe("free@example.com", "198.51.100.2")
        assertEquals(free, taken, "resend must not tell whether a verified account exists")
        assertEquals(listOf<Any?>(mapOf("attemptsLeft" to 4), mapOf("attemptsLeft" to 4)), free.wrongGuessAnswers)
    }
}
