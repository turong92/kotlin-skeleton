package dev.sumin.skeleton.app.sample

import dev.sumin.skeleton.account.mail.AccountMailTemplates
import dev.sumin.skeleton.account.mail.MailKind
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 소셜 모듈이 클래스패스에 있어도 client id 를 설정하지 않으면 아무것도 바뀌지 않는다 — `GET /auth/methods` 의 social 은 비어 있다 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SocialDefaultsIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var templates: AccountMailTemplates

    @Test
    fun `no social provider is offered until a client id is configured`() {
        mvc.get("/api/v1/auth/methods").andExpect {
            status { isOk() }
            jsonPath("$.value.social.length()") { value(0) }
        }
    }

    @Test
    fun `the sample sends its notice and code mails with an HTML part as well as text`() {
        for (kind in listOf(MailKind.IDENTITY_LINKED_NOTICE, MailKind.PASSWORD_CHANGED, MailKind.VERIFY_CODE)) {
            val mail = templates.render(kind, "ko", mapOf("code" to "123456", "method" to "x"), null)
            assertTrue(mail.html != null && "<table" in mail.html!!, "$kind has an HTML alternative")
            assertTrue(mail.text.isNotBlank())
        }
    }
}
