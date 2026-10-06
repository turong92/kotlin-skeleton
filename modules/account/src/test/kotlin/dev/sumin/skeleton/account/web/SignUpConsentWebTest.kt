package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.SimpleErrorCode
import dev.sumin.skeleton.common.consent.ConsentClaim
import dev.sumin.skeleton.common.consent.ConsentContext
import dev.sumin.skeleton.common.consent.SignUpConsentGate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RecordingGate : SignUpConsentGate {
    val checks = CopyOnWriteArrayList<List<ConsentClaim>>()
    val records = CopyOnWriteArrayList<Triple<String, List<ConsentClaim>, ConsentContext>>()
    @Volatile var refuse = false

    override fun check(claims: List<ConsentClaim>) {
        checks += claims
        if (refuse) throw ApplicationException("consent required", SimpleErrorCode("LEGAL.CONSENT_REQUIRED", HttpStatus.BAD_REQUEST, "Consent required"), data = mapOf("missing" to listOf<Any>()))
    }

    override fun record(accountId: String, claims: List<ConsentClaim>, context: ConsentContext) { records += Triple(accountId, claims, context) }
}

@TestConfiguration(proxyBeanMethods = false)
class GateBeans {
    @Bean fun recordingGate(): RecordingGate = RecordingGate()
}

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, GateBeans::class)
class SignUpConsentWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var gate: RecordingGate

    @BeforeEach
    fun clear() { mail.sent.clear(); gate.checks.clear(); gate.records.clear(); gate.refuse = false }

    private fun signUp(email: String, body: String, ip: String = "198.51.100.${(1..250).random()}", ua: String = "TestBrowser/1") =
        mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = ip; it }.header("User-Agent", ua).contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"tangerine-42-moon"$body}"""))

    @Test
    fun `the consents of the request reach the gate, and are recorded with the sign-up client once the code is verified`() {
        val email = "c${System.nanoTime()}@example.com"
        val r = signUp(email, ""","consents":[{"type":"terms","version":"v2","locale":"ko"},{"type":"marketing","version":"v1"}]""", ip = "198.51.100.77", ua = "TestBrowser/9")
            .andExpect(status().isAccepted).andReturn()

        assertEquals(listOf(listOf(ConsentClaim("terms", "v2", "ko"), ConsentClaim("marketing", "v1", null))), gate.checks)
        assertEquals(0, gate.records.size)

        val id = JsonPath.read<String>(r.response.contentAsString, "$.value.signUpId")
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}"""))
            .andExpect(status().isOk)

        val (_, claims, context) = gate.records.single()
        assertEquals(listOf(ConsentClaim("terms", "v2", "ko"), ConsentClaim("marketing", "v1", null)), claims)
        assertEquals(ConsentContext("198.51.100.77", "TestBrowser/9"), context)
    }

    @Test
    fun `a request without the field sends an empty list`() {
        signUp("d${System.nanoTime()}@example.com", "").andExpect(status().isAccepted)
        assertEquals(listOf(emptyList<ConsentClaim>()), gate.checks)
    }

    @Test
    fun `a refusal by the gate is the answer, and no mail goes out`() {
        gate.refuse = true
        signUp("e${System.nanoTime()}@example.com", ""","consents":[]""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LEGAL.CONSENT_REQUIRED"))
        assertEquals(0, mail.sent.size)
    }

    @Test
    fun `malformed or too many consents are a validation error before the gate is asked`() {
        fun bad(consents: String) = signUp("f${System.nanoTime()}@example.com", ""","consents":$consents""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        bad("""[{"type":"","version":"v1"}]""")
        bad("""[{"type":"terms"}]""")
        bad("""[{"type":"${"t".repeat(33)}","version":"v1"}]""")
        bad("""[{"type":"terms","version":"${"v".repeat(33)}"}]""")
        bad("""[{"type":"terms","version":"v1","locale":"${"l".repeat(36)}"}]""")
        bad((1..9).joinToString(",", "[", "]") { """{"type":"t$it","version":"v1"}""" })
        assertEquals(0, gate.checks.size)
    }
}
