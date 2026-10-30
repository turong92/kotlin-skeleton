package dev.sumin.skeleton.apikey

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.apikeytest.ApiKeyTestApplication
import dev.sumin.skeleton.apikeytest.ApiKeyTestBeans
import dev.sumin.skeleton.apikeytest.Mails
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private val ips = AtomicInteger()

/** 앱이 켜는 것들 - 접두사 · 키별 한도 · 만료 상한 (모듈 기본은 `apk` · 한도 끔 · 상한 없음) */
@SpringBootTest(
    classes = [ApiKeyTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.api-key.token-prefix=notes",
        "skeleton.api-key.rate-limit.capacity=3",
        "skeleton.api-key.max-lifetime=30d",
    ],
)
@AutoConfigureMockMvc
@Import(ApiKeyTestBeans::class)
class ApiKeyLimitsWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails

    private fun signUp(): String {
        val email = "l${System.nanoTime()}@example.com"
        val ip = "198.51.101.${100 + ips.getAndIncrement() % 100}"
        val sign = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted).andReturn()
        val signUpId = JsonPath.read<String>(sign.response.contentAsString, "$.value.signUpId")
        val done = mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$signUpId","code":"${mails.lastCode()}"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        return "Bearer " + JsonPath.read<String>(done, "$.value.accessToken")
    }

    private fun create(bearer: String, expiresAt: Instant?) =
        mvc.perform(post("/api/v1/api-keys").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"ci","scopes":["notes.read"],"currentPassword":"tangerine-42-moon"${expiresAt?.let { ""","expiresAt":"$it"""" } ?: ""}}"""))

    @Test
    fun `the app's prefix is on the token and the lifetime cap is enforced - no expiry, too far, or already past are 400`() {
        val bearer = signUp()
        val now = Instant.now()

        val token = JsonPath.read<String>(create(bearer, now.plus(29, ChronoUnit.DAYS)).andExpect(status().isCreated).andReturn().response.contentAsString, "$.value.token")
        assertTrue(token.startsWith("notes_"), token)
        create(bearer, null).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("API_KEY.EXPIRES_INVALID"))
        create(bearer, now.plus(31, ChronoUnit.DAYS)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("API_KEY.EXPIRES_INVALID"))
        create(bearer, now.minusSeconds(5)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("API_KEY.EXPIRES_INVALID"))
    }

    @Test
    fun `a key over its own limit is 429 with Retry-After while another key is not touched`() {
        val bearer = signUp()
        fun token() = JsonPath.read<String>(create(bearer, Instant.now().plus(1, ChronoUnit.DAYS)).andExpect(status().isCreated).andReturn().response.contentAsString, "$.value.token")
        val busy = token()
        val calm = token()

        repeat(3) { mvc.perform(get("/api/v1/probe/read").header("Authorization", "Bearer $busy")).andExpect(status().isOk) }
        mvc.perform(get("/api/v1/probe/read").header("Authorization", "Bearer $busy"))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("API_KEY.RATE_LIMITED")).andExpect(header().exists("Retry-After")).andExpect(header().string("X-RateLimit-Limit", "3"))

        mvc.perform(get("/api/v1/probe/read").header("Authorization", "Bearer $calm")).andExpect(status().isOk)
    }
}
