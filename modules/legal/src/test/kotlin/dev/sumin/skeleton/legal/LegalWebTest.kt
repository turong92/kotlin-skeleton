package dev.sumin.skeleton.legal

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.legaltest.LegalWebFakes
import dev.sumin.skeleton.legaltest.LegalWebTestApplication
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasSize
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [LegalWebTestApplication::class],
    properties = ["skeleton.legal.location=classpath:legal-web/", "skeleton.legal.facts.company-name=ACME", "skeleton.legal.facts.contact-email=a@b.c", "skeleton.legal.previous-version-grace=7d"],
)
@AutoConfigureMockMvc
@Import(LegalWebFakes::class)
class LegalWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeConsentStore
    @Autowired lateinit var clock: MutableClock

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")
    private val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_USER", "ROLE_ADMIN")

    @BeforeEach
    fun reset() {
        store.rows.clear()
        clock.now = Instant.parse("2026-10-07T00:00:00Z")
    }

    private fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.principal(who) else builder)

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun agreeAll(who: TestingAuthenticationToken = user) =
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"terms","version":"v2"},{"type":"privacy","version":"v1"}]}"""), who).andExpect(status().isOk)

    // ---- public reading ----

    @Test
    fun `the document list is public, one entry per type and locale of the current version, and cacheable`() {
        val result = call(get("/api/v1/legal/documents"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andExpect(header().exists("ETag"))
            .andExpect(jsonPath("$.values", hasSize<Any>(5)))
            .andExpect(jsonPath("$.values[0].type").value("terms"))
            .andExpect(jsonPath("$.values[0].locale").value("ko"))
            .andExpect(jsonPath("$.values[0].version").value("v2"))
            .andExpect(jsonPath("$.values[0].effectiveFrom").value("2026-10-01T00:00:00Z"))
            .andExpect(jsonPath("$.values[0].title").value("이용약관"))
            .andExpect(jsonPath("$.values[0].required").value(true))
            .andExpect(jsonPath("$.values[0].requiredAtSignUp").value(true))
            .andExpect(jsonPath("$.values[0].template").value(false))
            .andExpect(jsonPath("$.values[0].next").doesNotExist())
            .andExpect(jsonPath("$.values[?(@.type=='marketing')].required").value(false))
            .andReturn()
        val sha = JsonPath.read<String>(result.response.contentAsString, "$.values[0].sha256")
        assertEquals(64, sha.length)

        val etag = result.response.getHeader("ETag")!!
        call(get("/api/v1/legal/documents").header("If-None-Match", etag)).andExpect(status().isNotModified)
    }

    @Test
    fun `the detail is the current markdown with the facts filled in, an older version stays readable, an unknown one is a 404`() {
        call(get("/api/v1/legal/documents/terms"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=300, public"))
            .andExpect(jsonPath("$.value.version").value("v2"))
            .andExpect(jsonPath("$.value.locale").value("ko"))
            .andExpect(jsonPath("$.value.requestedLocale").value("ko"))
            .andExpect(jsonPath("$.value.current").value(true))
            .andExpect(jsonPath("$.value.markdown").value(org.hamcrest.Matchers.containsString("ACME 둘째 판")))
            .andExpect(jsonPath("$.value.markdown").value(org.hamcrest.Matchers.containsString("mailto:a@b.c")))
            .andExpect(jsonPath("$.value.sha256").value(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{64}$")))

        call(get("/api/v1/legal/documents/terms").param("version", "v1").param("locale", "en"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.version").value("v1"))
            .andExpect(jsonPath("$.value.locale").value("en"))
            .andExpect(jsonPath("$.value.current").value(false))
            .andExpect(jsonPath("$.value.markdown").value(org.hamcrest.Matchers.containsString("first edition")))

        call(get("/api/v1/legal/documents/terms").param("version", "v9")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("LEGAL.DOCUMENT_NOT_FOUND"))
        call(get("/api/v1/legal/documents/nope")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("LEGAL.DOCUMENT_NOT_FOUND"))
        call(get("/api/v1/legal/documents/Bad..Type")).andExpect(status().isNotFound)
    }

    @Test
    fun `a locale the version lacks falls back to the default and says so`() {
        call(get("/api/v1/legal/documents/marketing").param("locale", "en"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.locale").value("ko"))
            .andExpect(jsonPath("$.value.requestedLocale").value("en"))
    }

    @Test
    fun `the detail has an etag and answers 304 for it`() {
        val etag = call(get("/api/v1/legal/documents/privacy")).andReturn().response.getHeader("ETag")!!
        call(get("/api/v1/legal/documents/privacy").header("If-None-Match", etag)).andExpect(status().isNotModified)
    }

    // ---- signed-in status and agreeing ----

    @Test
    fun `the status needs a sign-in, and a fresh account is blocked on the required documents`() {
        call(get("/api/v1/legal/consents/me")).andExpect(status().isUnauthorized)

        call(get("/api/v1/legal/consents/me"), user)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.blocked").value(true))
            .andExpect(jsonPath("$.value.missing", hasSize<Any>(2)))
            .andExpect(jsonPath("$.value.missing[0].type").value("terms"))
            .andExpect(jsonPath("$.value.missing[0].version").value("v2"))
            .andExpect(jsonPath("$.value.missing[0].reason").value("NOT_AGREED"))
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].state").value("MISSING"))
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].current.locales[0]").value("ko"))
    }

    @Test
    fun `agreeing records what was shown and answers with the new status, with the client ip and user agent`() {
        call(post("/api/v1/legal/consents").header("User-Agent", "TestBrowser/1").with { it.remoteAddr = "203.0.113.5"; it }
            .json("""{"consents":[{"type":"terms","version":"v2","locale":"en"},{"type":"privacy","version":"v1"}],"source":"re-consent"}"""), user)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.blocked").value(false))
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].state").value("CURRENT"))
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].agreed.version").value("v2"))
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].agreed.source").value("re-consent"))

        val row = store.rows.first { it.type == "terms" }
        assertEquals(Subject.account("acc_user"), row.subject)
        assertEquals("en", row.locale)
        assertEquals("re-consent", row.source)
        assertEquals("203.0.113.5", row.ip)
        assertEquals("TestBrowser/1", row.userAgent)
    }

    @Test
    fun `the default source is consent and agreeing twice records one row`() {
        agreeAll()
        agreeAll()
        assertEquals(2, store.rows.size)
        assertEquals("consent", store.rows.first().source)
    }

    @Test
    fun `a stale version is a 409 with the version to show, and nothing is recorded`() {
        clock.now = Instant.parse("2026-10-09T00:00:00Z")   // the 7-day grace window of v2 is over
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"privacy","version":"v1"},{"type":"terms","version":"v1"}]}"""), user)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LEGAL.VERSION_STALE"))
            .andExpect(jsonPath("$.data.stale[0].type").value("terms"))
            .andExpect(jsonPath("$.data.stale[0].requiredVersion").value("v2"))
        assertEquals(0, store.rows.size)
    }

    @Test
    fun `the previous version is accepted inside the grace window of seven days`() {
        // v2 took effect on 2026-10-01; the clock is at 2026-10-07 (grace 7d runs until 10-08)
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"terms","version":"v1"},{"type":"privacy","version":"v1"}]}"""), user).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].state").value("GRACE"))
            .andExpect(jsonPath("$.value.blocked").value(false))
        clock.now = Instant.parse("2026-10-08T00:00:00Z")
        call(get("/api/v1/legal/consents/me"), user)
            .andExpect(jsonPath("$.value.blocked").value(true))
            .andExpect(jsonPath("$.value.missing[0].reason").value("STALE"))
            .andExpect(jsonPath("$.value.items[?(@.type=='terms')].state").value("OUTDATED"))
    }

    @Test
    fun `an unknown type is a 400 and named`() {
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"nope","version":"v1"}]}"""), user)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LEGAL.UNKNOWN_DOCUMENT"))
            .andExpect(jsonPath("$.data.types[0]").value("nope"))
    }

    @Test
    fun `the request body is validated`() {
        fun bad(body: String) = call(post("/api/v1/legal/consents").json(body), user).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        bad("""{"consents":[]}""")
        bad("""{}""")
        bad("""{"consents":[{"type":"terms","version":"v2"},{"type":"terms","version":"v2"}]}""")
        bad("""{"consents":[{"type":"Terms!","version":"v2"}]}""")
        bad("""{"consents":[{"type":"terms","version":""}]}""")
        bad("""{"consents":[{"type":"terms","version":"v2"}],"source":"sign-up"}""")
        bad("""{"consents":[{"type":"terms","version":"v2"}],"source":"Bad Source"}""")
        bad("""{"consents":[${(1..21).joinToString(",") { """{"type":"t$it","version":"v1"}""" }}]}""")
        assertEquals(0, store.rows.size)
    }

    @Test
    fun `an optional agreement can be withdrawn, a required one cannot, and withdrawing twice is harmless`() {
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"marketing","version":"v1"}]}"""), user).andExpect(status().isOk)

        call(post("/api/v1/legal/consents/marketing/withdraw"), user)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.items[?(@.type=='marketing')].state").value("WITHDRAWN"))
        call(post("/api/v1/legal/consents/marketing/withdraw"), user).andExpect(status().isOk)
        assertEquals(listOf(ConsentAction.AGREED, ConsentAction.WITHDRAWN), store.rows.map { it.action })

        call(post("/api/v1/legal/consents/terms/withdraw"), user).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LEGAL.WITHDRAWAL_NOT_ALLOWED"))
        call(post("/api/v1/legal/consents/nope/withdraw"), user).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LEGAL.UNKNOWN_DOCUMENT"))
        call(post("/api/v1/legal/consents/marketing/withdraw")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the history is the caller's own events, newest first, without the ip`() {
        agreeAll()
        agreeAll(other)
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"marketing","version":"v1"}]}"""), user)
        call(post("/api/v1/legal/consents/marketing/withdraw"), user)

        call(get("/api/v1/legal/consents/me/history").param("size", "3"), user)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(3)))
            .andExpect(jsonPath("$.values[0].type").value("marketing"))
            .andExpect(jsonPath("$.values[0].action").value("WITHDRAWN"))
            .andExpect(jsonPath("$.values[1].action").value("AGREED"))
            .andExpect(jsonPath("$.values[0].ip").doesNotExist())
            .andExpect(jsonPath("$.values[0].userAgent").doesNotExist())
            .andExpect(jsonPath("$.pagination.totalElements").value(4))
            .andExpect(jsonPath("$.pagination.hasNext").value(true))
        call(get("/api/v1/legal/consents/me/history")).andExpect(status().isUnauthorized)
    }

    // ---- admin ----

    @Test
    fun `the admin endpoints need the admin role`() {
        call(get("/api/v1/legal/admin/consents")).andExpect(status().isUnauthorized)
        call(get("/api/v1/legal/admin/consents"), user).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        call(get("/api/v1/legal/admin/documents"), user).andExpect(status().isForbidden)
    }

    @Test
    fun `the admin sees every subject's events with the ip, filtered by subject, type and action`() {
        agreeAll()
        agreeAll(other)
        call(post("/api/v1/legal/consents").json("""{"consents":[{"type":"marketing","version":"v1"}]}"""), user)
        call(post("/api/v1/legal/consents/marketing/withdraw"), user)

        call(get("/api/v1/legal/admin/consents"), admin)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pagination.totalElements").value(6))
            .andExpect(jsonPath("$.values[0].subjectType").value("account"))
            .andExpect(jsonPath("$.values[0].subjectId").value("acc_user"))
            .andExpect(jsonPath("$.values[0].sha256").exists())
            .andExpect(jsonPath("$.values[0].ip").exists())
        call(get("/api/v1/legal/admin/consents").param("subjectId", "acc_other"), admin).andExpect(jsonPath("$.values", hasSize<Any>(2)))
        call(get("/api/v1/legal/admin/consents").param("type", "marketing").param("action", "WITHDRAWN"), admin).andExpect(jsonPath("$.values", hasSize<Any>(1)))
        call(get("/api/v1/legal/admin/consents").param("action", "bogus"), admin).andExpect(status().isBadRequest)
    }

    @Test
    fun `the admin sees every version including status, pin state and the facts a text still lacks`() {
        call(get("/api/v1/legal/admin/documents"), admin)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(4)))
            .andExpect(jsonPath("$.values[?(@.type=='terms' && @.version=='v2')].status").value("REVIEWED"))
            .andExpect(jsonPath("$.values[?(@.type=='terms' && @.version=='v2')].current").value(true))
            .andExpect(jsonPath("$.values[?(@.type=='terms' && @.version=='v1')].current").value(false))
            .andExpect(jsonPath("$.values[?(@.type=='terms' && @.version=='v2')].pinned").value(true))
            .andExpect(jsonPath("$.values[?(@.type=='terms' && @.version=='v2')].sha256.ko").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{64}$"))))
            .andExpect(jsonPath("$.values[?(@.type=='terms' && @.version=='v2')].missingFacts[*]").isEmpty)
    }
}
