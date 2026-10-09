package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B4 — 실시간 아이디 확인(§6.2): 형식 오류는 저장소도 한도도 건드리지 않는다, 쓸 수 없는 이유 셋은 같은 답, IP 한도, 가입 3번과 같은 판정 함수 */
class LoginIdAvailabilityTest {
    private fun props(checkPerIp: Int = 30, reserved: List<String> = listOf("admin")) = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        loginId = AccountProperties.LoginId(enabled = true, reserved = reserved, checkPerIp = checkPerIp),
    )

    private fun AccountHarness.register(email: String, loginId: String) {
        val o = registration.signUp(SignUpCommand(email, "tangerine-42-moon", null, null, null, "203.0.113.1", null, loginId = loginId))
        signUpIds[Emails.normalize(email)] = o.signUpId!!
        verify(email)
    }

    @Test
    fun `a free id is available and the answer carries the normalized id`() {
        val h = AccountHarness(props())
        assertEquals(LoginIdAvailability("sumin.kim", true), h.availability.check("  Sumin.Kim ", "198.51.100.1"))
    }

    @Test
    fun `a malformed id is INVALID with the field code - no repository read and no allowance used`() {
        val h = AccountHarness(props(checkPerIp = 2))
        val before = h.callLog.calls.size
        listOf(null to "Required", "" to "Required", "su" to "Size", "a..bcd" to "Pattern", "bad id" to "Pattern", "12345" to "Pattern").forEach { (raw, code) ->
            val a = h.availability.check(raw, "198.51.100.1")
            assertEquals(false, a.available); assertEquals("INVALID", a.reason); assertEquals(code, a.code, "raw=$raw")
        }
        assertEquals("su", h.availability.check("Su", "198.51.100.1").normalized, "INVALID still reports the normalized text")
        assertEquals(before, h.callLog.calls.size, "a form error never reads the account repository")
        // the allowance of 2 is untouched by the 7 form errors above
        h.availability.check("freeone", "198.51.100.1"); h.availability.check("freetwo", "198.51.100.1")
        assertEquals("ACCOUNT.RATE_LIMITED", assertFailsWith<ApplicationException> { h.availability.check("freethree", "198.51.100.1") }.errorCode.code)
    }

    @Test
    fun `a huge input is INVALID Size with a cut-down normalized text - no recursion, no allowance used (I1)`() {
        val h = AccountHarness(props(checkPerIp = 1))
        val huge = "a" + ".a".repeat(50_000)
        val a = h.availability.check(huge, "198.51.100.1")
        assertEquals("INVALID", a.reason); assertEquals("Size", a.code)
        assertTrue(a.normalized.length <= LoginIds.COLUMN_LENGTH, "the answer does not echo a 100KB text back")
        assertTrue(h.availability.check("freeone", "198.51.100.1").available, "the allowance of 1 is untouched")
    }

    @Test
    fun `reserved, live and erased ids all answer TAKEN with nothing to tell them apart`() {
        val h = AccountHarness(props())
        h.register("owner@example.com", "liveone"); h.register("gone@example.com", "goneone")
        h.deletion.delete(h.repo.findByEmail("gone@example.com")!!.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(java.time.Duration.ofDays(31))
        assertEquals(1, h.purge.purgeDue())
        assertNull(h.repo.findByLoginId("goneone"))
        val answers = listOf("ad.min", "ADMIN", "liveone", "LiveOne", "goneone").map { it to h.availability.check(it, "198.51.100.1") }
        answers.forEach { (raw, a) ->
            assertEquals(false, a.available, raw); assertEquals("TAKEN", a.reason, raw); assertNull(a.code, raw)
            assertEquals(LoginIds.normalize(raw), a.normalized)
        }
    }

    @Test
    fun `the answer is the sign-up's own decision - AccountCore loginIdAvailable - so what sign-up refuses is never reported free`() {
        val h = AccountHarness(props())
        h.register("owner@example.com", "liveone")
        listOf("liveone", "admin", "brandnew").forEach { id ->
            assertEquals(h.core.loginIdAvailable(id), h.availability.check(id, "198.51.100.1").available, id)
        }
    }

    @Test
    fun `the three reasons do the same repository work - reserved, live, erased and free all read both tables (M1)`() {
        val h = AccountHarness(props())
        h.register("owner@example.com", "liveone")
        h.register("gone@example.com", "goneone")
        h.deletion.delete(h.repo.findByEmail("gone@example.com")!!.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(java.time.Duration.ofDays(31)); h.purge.purgeDue()
        val work = listOf("admin", "liveone", "goneone", "brandnew").map { id ->
            h.callLog.calls.clear()
            h.core.loginIdAvailable(id)
            id to h.callLog.calls.map { it.second }
        }
        assertEquals(1, work.map { it.second }.toSet().size, "the repository calls differ by reason: $work")
        assertEquals(listOf("findByLoginId", "loginIdUsed"), work.first().second)
    }

    @Test
    fun `over the allowance is 429 ACCOUNT_RATE_LIMITED with a retry time, per IP key`() {
        val h = AccountHarness(props(checkPerIp = 3))
        repeat(3) { assertTrue(h.availability.check("free$it", "198.51.100.1").available) }
        val e = assertFailsWith<dev.sumin.skeleton.account.abuse.RateLimitedException> { h.availability.check("free9", "198.51.100.1") }
        assertEquals("ACCOUNT.RATE_LIMITED", e.errorCode.code); assertTrue(e.retryAfterSeconds >= 1)
        assertTrue(h.availability.check("free9", "198.51.100.2").available, "another IP has its own allowance")
    }

    @Test
    fun `a TAKEN answer uses the allowance like a free one, and the candidate is in no event`() {
        val h = AccountHarness(props(checkPerIp = 2))
        h.register("owner@example.com", "liveone")
        val events = h.events.all.size
        h.availability.check("liveone", "198.51.100.1"); h.availability.check("liveone", "198.51.100.1")
        assertFailsWith<ApplicationException> { h.availability.check("liveone", "198.51.100.1") }
        assertEquals(events, h.events.all.size, "checking publishes no event")
    }
}
