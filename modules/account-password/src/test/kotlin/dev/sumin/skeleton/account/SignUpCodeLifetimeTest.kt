package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.MailKind
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** 가입 인증 코드도 다른 코드 메일들처럼 10분이라고 말하고 그때 정말 만료된다 (나머지 코드 메일은 `account` 의 CodeLifetimeTest) */
class SignUpCodeLifetimeTest {
    private val h = AccountHarness()

    @Test
    fun `the sign-up code mail says ten minutes, and the attempt really expires then`() {
        val outcome = h.signUp("new@example.com")
        assertEquals("10", h.mailer.of(MailKind.VERIFY_CODE).last().vars["minutes"])
        assertEquals(h.time.now().plus(Duration.ofMinutes(10)), outcome.expiresAt)
    }
}
