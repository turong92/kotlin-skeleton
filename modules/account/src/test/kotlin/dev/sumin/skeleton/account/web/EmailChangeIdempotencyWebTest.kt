package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountFixtures
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 이메일 변경은 `@IdempotentOperation` — 인증 실패는 키에 붙지 않는다 (`idempotency` 가 시험 클래스패스에 있다) */
@SpringBootTest(classes = [AccountWebTestApplication::class], properties = ["skeleton.account.mail.link-base-url=https://app.example.com"])
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class EmailChangeIdempotencyWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var fixtures: AccountFixtures

    private fun body(target: String, password: String) = """{"newEmail":"$target","currentPassword":"$password"}"""
    private fun change(auth: String, key: String, target: String, password: String) =
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(target, password)))

    @Test
    fun `an authentication failure is never cached under an Idempotency-Key - the retry with the right password runs, a success is still replayed`() {
        val email = "idem${System.nanoTime()}@example.com"
        fixtures.create(email)
        val auth = fixtures.bearer(email)
        val key = java.util.UUID.randomUUID().toString()
        val target = "target${System.nanoTime()}@example.com"
        mail.sent.clear()
        change(auth, key, target, "wrong-password-1").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        // the mistyped password must not stick to the key: another attempt really executes (and fails again on its own merits - not a stored replay)
        change(auth, key, target, "another-wrong-pass-2").andExpect(status().isBadRequest).andExpect(header().string("X-Idempotency-Replayed", "false"))
        // the right password with the same key now works
        change(auth, key, target, "tangerine-42-moon").andExpect(status().isAccepted).andExpect(header().string("X-Idempotency-Replayed", "false"))
        // and a success is replayed, not executed twice
        change(auth, key, target, "tangerine-42-moon").andExpect(status().isAccepted).andExpect(header().string("X-Idempotency-Replayed", "true"))
        assertEquals(1, mail.of(MailKind.EMAIL_CHANGE_CODE).size)
    }
}
