package dev.sumin.skeleton.app.sample

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 진짜 `POST /auth/login`(auth-password)에 진짜 한도 · 기록 고리(account-password 의 LoginThrottle · LoginRecorder)가 붙은 조합 —
 * 두 모듈은 서로를 모르고 auth 의 `LoginHooks` 계약으로만 이어지므로(각 모듈 시험은 대역으로 본다), 이어진 모양은 앱에서만 볼 수 있다.
 */
@SpringBootTest(
    properties = [
        "spring.config.import=classpath:test-seeds.yml", "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.login.per-account=3", "skeleton.account.audit.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PasswordLoginHooksIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    private fun login(email: String, password: String) =
        mvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = """{"email":"$email","password":"$password"}""" }

    private fun audits(type: String, accountId: String) =
        jdbc.sql("select count(*) from account_audit where type = :t and account_id = :a").param("t", type).param("a", accountId).query(Long::class.java).single()

    @Test
    fun `wrong passwords hit the real throttle and a good login is recorded`() {
        repeat(3) { login("user@example.com", "wrong-password-9").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.INVALID_CREDENTIALS") } } }
        login("user@example.com", "password").andExpect { status { isTooManyRequests() }; jsonPath("$.code") { value("AUTH.TOO_MANY_ATTEMPTS") } }
        assertEquals(3L, audits("LOGIN_FAILURE", "acc_user"), "every wrong attempt reached the recorder")

        login("moderator@example.com", "password").andExpect { status { isOk() } }
        assertEquals(1L, audits("LOGIN_SUCCESS", "acc_moderator"))
        assertNotNull(jdbc.sql("select last_login_at from accounts where id = 'acc_moderator'").query { rs, _ -> rs.getObject("last_login_at") }.single(), "the recorder stamped lastLoginAt")
    }
}
