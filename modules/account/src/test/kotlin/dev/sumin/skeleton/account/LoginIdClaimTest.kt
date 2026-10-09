package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B6 — 「아이디 정하기」(스펙 §8.1): 저장소의 다섯 결과, 이메일 없이도 된다, 한도는 시도를 센다, 이벤트에는 아이디가 없다 */
class LoginIdClaimTest {
    private fun props(claimLimit: Int = 10, reserved: List<String> = listOf("admin")) = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        loginId = AccountProperties.LoginId(enabled = true, reserved = reserved, claimLimit = claimLimit),
    )

    /** 소셜로만 들어온 계정 — 아이디도 이메일도 없다 */
    private fun AccountHarness.shell(id: String = "acc_shell", email: String? = null, loginId: String? = null, status: AccountStatus = AccountStatus.ACTIVE): Account {
        val now = time.now()
        val a = Account(id, email, email != null, status, setOf("USER"), null, "ko", "Asia/Seoul", now, now, loginId = loginId)
        repo.insert(a, listOf(Identity("idn_$id", id, "kakao", "k-$id", true, createdAt = now)), core.loginIdHashOf(loginId))
        return a
    }

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    @Test
    fun `claiming sets the normalized id on an account that has no email`() {
        val h = AccountHarness(props())
        val a = h.shell()
        h.loginIds.claim(a.id, "  Sumin.Kim ", "203.0.113.1")
        assertEquals("sumin.kim", h.repo.findById(a.id)!!.loginId)
        assertNull(h.repo.findById(a.id)!!.email, "the id does not need an address")
        assertEquals(a.id, h.repo.findByLoginId("sumin.kim")!!.id)
    }

    @Test
    fun `a live id, a reserved id and an erased id are all the same TAKEN`() {
        val h = AccountHarness(props())
        h.shell("acc_live", loginId = "liveone")
        h.registration.signUp(SignUpCommand("gone@example.com", "tangerine-42-moon", null, null, null, "203.0.113.1", null, loginId = "goneone")).also { h.signUpIds["gone@example.com"] = it.signUpId!! }
        h.verify("gone@example.com")
        h.deletion.delete(h.repo.findByEmail("gone@example.com")!!.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(Duration.ofDays(31))
        assertEquals(1, h.purge.purgeDue())
        assertNull(h.repo.findByLoginId("goneone"), "the erased row lost its id")
        val a = h.shell("acc_new")
        listOf("liveone", "LiveOne", "admin", "ad.min", "goneone").forEach { raw ->
            assertEquals("ACCOUNT.LOGIN_ID_TAKEN", code { h.loginIds.claim(a.id, raw, "203.0.113.1") }, raw)
        }
        assertNull(h.repo.findById(a.id)!!.loginId)
    }

    @Test
    fun `an account that already has an id cannot change it`() {
        val h = AccountHarness(props())
        val a = h.shell(loginId = "first.one")
        assertEquals("ACCOUNT.LOGIN_ID_ALREADY_SET", code { h.loginIds.claim(a.id, "second.one", "203.0.113.1") })
        assertEquals("first.one", h.repo.findById(a.id)!!.loginId)
        assertTrue(h.core.loginIdAvailable("second.one"), "a refused change leaves the new id free")
    }

    @Test
    fun `a suspended account cannot choose an id - NOT_ACTIVE`() {
        val h = AccountHarness(props())
        val a = h.shell(status = AccountStatus.SUSPENDED)
        assertEquals("ACCOUNT.NOT_ACTIVE", code { h.loginIds.claim(a.id, "someone.new", "203.0.113.1") })
        assertNull(h.repo.findById(a.id)!!.loginId)
    }

    @Test
    fun `an unknown account is NOT_FOUND`() {
        val h = AccountHarness(props())
        assertEquals("ACCOUNT.NOT_FOUND", code { h.loginIds.claim("acc_nobody", "someone.new", "203.0.113.1") })
    }

    @Test
    fun `a malformed id is a field error on loginId and is not counted as an attempt`() {
        val h = AccountHarness(props(claimLimit = 2))
        val a = h.shell()
        listOf(null to "Required", "su" to "Size", "a..bcd" to "Pattern", "12345" to "Pattern").forEach { (raw, fieldCode) ->
            val e = assertFailsWith<FieldValidationException> { h.loginIds.claim(a.id, raw, null) }
            assertEquals("loginId", e.field); assertEquals(fieldCode, e.fieldCode)
        }
        h.loginIds.claim(a.id, "fine.one", null)   // both attempts of the allowance are still there
        assertEquals("fine.one", h.repo.findById(a.id)!!.loginId)
    }

    @Test
    fun `every attempt counts - failures too - and past the limit it is 429 even for a free id`() {
        val h = AccountHarness(props(claimLimit = 3))
        h.shell("acc_live", loginId = "liveone")
        val a = h.shell()
        repeat(3) { assertEquals("ACCOUNT.LOGIN_ID_TAKEN", code { h.loginIds.claim(a.id, "liveone", "203.0.113.1") }) }
        assertEquals("ACCOUNT.RATE_LIMITED", code { h.loginIds.claim(a.id, "free.one", "203.0.113.1") })
        assertNull(h.repo.findById(a.id)!!.loginId, "the refused attempt changed nothing")
        // the window is per account: another account is not affected, and the window passing frees this one
        val b = h.shell("acc_other")
        h.loginIds.claim(b.id, "other.one", null)
        h.time.advance(Duration.ofHours(1).plusSeconds(1))
        h.loginIds.claim(a.id, "free.one", null)
        assertEquals("free.one", h.repo.findById(a.id)!!.loginId)
    }

    @Test
    fun `the event says via gate and never carries the id`() {
        val h = AccountHarness(props())
        val a = h.shell()
        assertEquals("ACCOUNT.LOGIN_ID_TAKEN", code { h.loginIds.claim(a.id, "admin", "203.0.113.1") })
        assertFalse(AccountEventType.LOGIN_ID_SET in h.events.types(), "a refused claim is not an event")
        h.loginIds.claim(a.id, "Sumin.Kim", "203.0.113.1")
        val e = h.events.all.single { it.type == AccountEventType.LOGIN_ID_SET }
        assertEquals(a.id, e.accountId)
        assertEquals(mapOf("via" to "gate"), e.detail)
        assertFalse(h.events.all.any { ev -> "sumin" in ev.toString().lowercase() || "admin" in ev.detail.values }, "no event mentions the id")
    }

    // ---- I3 — 계정을 새로 만들어도 IP 한도는 이어진다

    @Test
    fun `the same IP spends one claim allowance across accounts - a fresh account does not reopen it (I3)`() {
        val h = AccountHarness(props(claimLimit = 3))
        repeat(3) { h.loginIds.claim(h.shell("acc_$it").id, "burnt.id$it", "203.0.113.1") }
        val e = assertFailsWith<dev.sumin.skeleton.account.abuse.RateLimitedException> { h.loginIds.claim(h.shell("acc_4").id, "burnt.id4", "203.0.113.1") }
        assertEquals("ACCOUNT.RATE_LIMITED", e.errorCode.code); assertTrue(e.retryAfterSeconds >= 1)
        assertNull(h.repo.findById("acc_4")!!.loginId)
        assertFalse(h.repo.loginIdUsed(h.core.loginIdHashOf("burnt.id4")!!), "a refused attempt burns nothing")
        h.loginIds.claim("acc_4", "other.ip.id", "203.0.113.2")   // another IP has its own allowance
    }

    @Test
    fun `failed attempts count against the IP too, and a form error does not (I3)`() {
        val h = AccountHarness(props(claimLimit = 2))
        h.shell("acc_live", loginId = "liveone")
        repeat(5) { assertFailsWith<FieldValidationException> { h.loginIds.claim(h.shell("acc_f$it").id, "x", "203.0.113.1") } }
        repeat(2) { assertEquals("ACCOUNT.LOGIN_ID_TAKEN", code { h.loginIds.claim(h.shell("acc_t$it").id, "liveone", "203.0.113.1") }) }
        assertEquals("ACCOUNT.RATE_LIMITED", code { h.loginIds.claim(h.shell("acc_last").id, "liveone", "203.0.113.1") })
    }

    @Test
    fun `the IP bucket uses the limit key, not the event IP, and a missing key skips it`() {
        val h = AccountHarness(props(claimLimit = 1))
        h.loginIds.claim(h.shell("acc_1").id, "first.one", "203.0.113.1", "key-a")
        assertEquals("ACCOUNT.RATE_LIMITED", code { h.loginIds.claim(h.shell("acc_2").id, "second.one", "203.0.113.9", "key-a") })
        h.loginIds.claim(h.shell("acc_5").id, "fifth.one", "203.0.113.1", "key-b")   // another limit key, same event IP
        h.loginIds.claim(h.shell("acc_3").id, "third.one", null, null)
        h.loginIds.claim(h.shell("acc_4").id, "fourth.one", null, null)
    }
}
