package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.adswebtest.MutableClock
import dev.sumin.skeleton.adswebtest.RecordingIssuer
import dev.sumin.skeleton.adswebtest.WebRewardFakes
import dev.sumin.skeleton.adswebtest.WebRewardTestApplication
import dev.sumin.skeleton.adswebtest.account
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val BASE = "/api/v1/ads/web-rewarded/sessions"
private const val OPEN = """{"purpose":"unlock-episode"}"""

/** 스펙의 API 계약 — 로그인 필요 · 열기 200 · 남의 세션 404 · purpose 모양 400. 규칙은 서비스 시험이 본다 */
@SpringBootTest(
    classes = [WebRewardTestApplication::class],
    properties = ["skeleton.ads-web-rewarded.enabled=true", "skeleton.ads-web-rewarded.reward-type=point", "skeleton.ads-web-rewarded.reward-params.amount=1"],
)
@AutoConfigureMockMvc
@Import(WebRewardFakes::class)
class WebRewardedWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var issuer: RecordingIssuer

    @BeforeTest
    fun reset() {
        issuer.issued.clear()
        clock.now = java.time.Instant.parse("2026-11-18T03:00:00Z")
    }

    private fun open(who: String = "a1") =
        mvc.perform(post(BASE).principal(account(who)).contentType(MediaType.APPLICATION_JSON).content(OPEN))

    private fun sessionId(who: String = "a1"): String {
        val body = open(who).andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"sessionId\":\"(wrs_[^\"]+)\"").find(body)!!.groupValues[1]
    }

    @Test
    fun `anonymous callers are 401 on both paths and nothing is paid`() {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(OPEN)).andExpect(status().isUnauthorized)
        mvc.perform(post("$BASE/wrs_x/grant")).andExpect(status().isUnauthorized)
        assertEquals(0, issuer.issued.size)
    }

    @Test
    fun `open then grant matches the contract - point mode has a reward and no token`() {
        val id = sessionId()
        clock.advance(Duration.ofSeconds(8))

        mvc.perform(post("$BASE/$id/grant").principal(account("a1")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.sessionId").value(id))
            .andExpect(jsonPath("$.value.purpose").value("unlock-episode"))
            .andExpect(jsonPath("$.value.reward.type").value("point"))
            .andExpect(jsonPath("$.value.reward.ref").exists())
            .andExpect(jsonPath("$.value.token").doesNotExist())
    }

    @Test
    fun `another account's session is 404 and the owner can still grant it`() {
        val id = sessionId("a1")
        clock.advance(Duration.ofSeconds(8))

        mvc.perform(post("$BASE/$id/grant").principal(account("a2")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ADS_WEB_REWARDED.SESSION_NOT_FOUND"))
        assertEquals(0, issuer.issued.size)

        mvc.perform(post("$BASE/$id/grant").principal(account("a1"))).andExpect(status().isOk)
    }

    @Test
    fun `a grant before the minimum time is 409 TOO_EARLY and a second grant is 409 SESSION_USED`() {
        val id = sessionId()
        mvc.perform(post("$BASE/$id/grant").principal(account("a1")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ADS_WEB_REWARDED.TOO_EARLY"))

        clock.advance(Duration.ofSeconds(8))
        mvc.perform(post("$BASE/$id/grant").principal(account("a1"))).andExpect(status().isOk)
        mvc.perform(post("$BASE/$id/grant").principal(account("a1")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ADS_WEB_REWARDED.SESSION_USED"))
        assertEquals(1, issuer.issued.size)
    }

    @Test
    fun `a purpose of the wrong shape is 400`() {
        mvc.perform(post(BASE).principal(account("a1")).contentType(MediaType.APPLICATION_JSON).content("""{"purpose":"Unlock Episode"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        mvc.perform(post(BASE).principal(account("a1")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest)
    }
}
