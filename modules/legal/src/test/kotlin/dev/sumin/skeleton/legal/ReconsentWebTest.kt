package dev.sumin.skeleton.legal

import dev.sumin.skeleton.legaltest.LegalWebFakes
import dev.sumin.skeleton.legaltest.LegalWebTestApplication
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [LegalWebTestApplication::class],
    properties = [
        "skeleton.legal.location=classpath:legal-web/", "skeleton.legal.facts.company-name=ACME", "skeleton.legal.facts.contact-email=a@b.c",
        "skeleton.legal.reconsent.enabled=true", "skeleton.legal.previous-version-grace=1d",
    ],
)
@AutoConfigureMockMvc
@Import(LegalWebFakes::class)
class ReconsentWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeConsentStore
    @Autowired lateinit var clock: MutableClock

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")

    @BeforeEach
    fun reset() {
        store.rows.clear()
        clock.now = Instant.parse("2026-10-07T00:00:00Z")
    }

    private fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.principal(who) else builder)

    private fun agree(vararg pairs: Pair<String, String>) =
        call(
            post("/api/v1/legal/consents").contentType(MediaType.APPLICATION_JSON)
                .content("""{"consents":[${pairs.joinToString(",") { """{"type":"${it.first}","version":"${it.second}"}""" }}]}"""),
            user,
        ).andExpect(status().isOk)

    @Test
    fun `an account that never agreed is answered 403 on a protected endpoint, with the list to agree to`() {
        call(get("/api/v1/things"), user)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("LEGAL.RECONSENT_REQUIRED"))
            .andExpect(jsonPath("$.data.missing[0].type").value("terms"))
            .andExpect(jsonPath("$.data.missing[0].version").value("v2"))
            .andExpect(jsonPath("$.data.missing[0].reason").value("NOT_AGREED"))
            .andExpect(jsonPath("$.data.missing[1].type").value("privacy"))
    }

    @Test
    fun `after agreeing the same call goes through`() {
        agree("terms" to "v2", "privacy" to "v1")
        call(get("/api/v1/things"), user).andExpect(status().isOk).andExpect(jsonPath("$.ok").value(true))
    }

    @Test
    fun `the legal, auth and account endpoints are never blocked so the user can fix it, leave or delete the account`() {
        call(get("/api/v1/legal/consents/me"), user).andExpect(status().isOk)
        call(get("/api/v1/auth/probe"), user).andExpect(status().isOk)
        call(get("/api/v1/account/probe"), user).andExpect(status().isOk)
        call(get("/elsewhere/probe"), user).andExpect(status().isOk)
    }

    @Test
    fun `anonymous requests are not the filter's business`() {
        call(get("/api/v1/things")).andExpect(status().isOk)
    }

    @Test
    fun `a new version past the grace window blocks an account that agreed to the old one, inside the window it does not`() {
        clock.now = Instant.parse("2026-09-30T00:00:00Z")
        agree("terms" to "v1", "privacy" to "v1")
        call(get("/api/v1/things"), user).andExpect(status().isOk)

        clock.now = Instant.parse("2026-10-01T12:00:00Z")   // v2 took effect 12 h ago, grace is one day
        call(get("/api/v1/things"), user).andExpect(status().isOk)

        clock.now = Instant.parse("2026-10-02T00:00:00Z")
        call(get("/api/v1/things"), user)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("LEGAL.RECONSENT_REQUIRED"))
            .andExpect(jsonPath("$.data.missing[0].type").value("terms"))
            .andExpect(jsonPath("$.data.missing[0].reason").value("STALE"))

        agree("terms" to "v2")
        call(get("/api/v1/things"), user).andExpect(status().isOk)
    }

    @Test
    fun `an optional document that was never agreed to never blocks`() {
        agree("terms" to "v2", "privacy" to "v1")
        call(get("/api/v1/things"), user).andExpect(status().isOk)
    }
}
