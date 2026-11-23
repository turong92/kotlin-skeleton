package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 확인된 계정(또는 삭제 유예 중인 계정)이 가진 주소의 **옛 가입 시도**가 맞는 코드로 끝나도 그 계정은 건드려지지 않는다 — `RegistrationService.createOrProve` 의 그 한 줄을 결정적으로 지킨다.
 * 시도는 그 주소가 비어 있을 때 열렸고, 그 사이 계정이 (가입 시도가 아닌 길로 — 소셜 · 시드 · 관리자 · 이메일 변경) 확인된 채 생겼다. 다른 가입 시도의 verify 는 같은 주소의 시도를 지우지 않는 길이다.
 */
class VerifiedAccountNeverOverwrittenTest {
    private val h = AccountHarness()
    private val now: Instant = h.time.now()
    private fun err(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }

    private fun openAttempt(email: String): SignUpOutcome = h.signUp(email, password = "attacker-pass-777")

    private fun ownerAccount(email: String, status: AccountStatus = AccountStatus.ACTIVE, verified: Boolean = true) {
        h.repo.insert(
            Account("acc_owner", email, verified, status, setOf("USER"), null, null, null, now, now),
            listOf(Identity("idn_owner", "acc_owner", "password", email, true, secret = h.hasher.hash("owner-pass-999"), createdAt = now), Identity("idn_g", "acc_owner", "google", "g-owner", true, createdAt = now)),
        )
    }

    @Test
    fun `the right code of a stale attempt does not replace a verified account's password, add a login, or sign anyone in`() {
        val email = "ann@example.com"
        val attempt = openAttempt(email)
        val code = h.lastCode(email)
        ownerAccount(email)

        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(attempt.signUpId!!, code, "203.0.113.1") }.errorCode.code)

        assertTrue(h.hasher.matches("owner-pass-999", h.repo.findIdentity("password", email)!!.secret!!), "the owner's password is untouched")
        assertEquals(setOf("password", "google"), h.repo.identitiesOf("acc_owner").map { it.method }.toSet())
        assertEquals(null, h.repo.findById("acc_owner")!!.lastLoginAt)
        assertTrue(dev.sumin.skeleton.account.events.AccountEventType.LOGIN_SUCCESS !in h.events.types())
    }

    @Test
    fun `an account in its deletion grace period is not taken over by a stale attempt either`() {
        val email = "bob@example.com"
        val attempt = openAttempt(email)
        val code = h.lastCode(email)
        ownerAccount(email, AccountStatus.DELETED, verified = false)   // unverified, so ONLY the deletion check can stop the proof

        assertEquals("ACCOUNT.CODE_EXPIRED", err { h.registration.verifyEmail(attempt.signUpId!!, code, "203.0.113.1") }.errorCode.code)

        assertTrue(h.hasher.matches("owner-pass-999", h.repo.findIdentity("password", email)!!.secret!!))
        assertEquals(AccountStatus.DELETED, h.repo.findById("acc_owner")!!.status)
        assertEquals(false, h.repo.findById("acc_owner")!!.emailVerified, "no proof was applied")
    }
}
