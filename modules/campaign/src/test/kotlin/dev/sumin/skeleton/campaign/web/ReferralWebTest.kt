package dev.sumin.skeleton.campaign.web

import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.FakeCampaignStore
import dev.sumin.skeleton.campaign.Referral
import dev.sumin.skeleton.campaign.ReferralFlag
import dev.sumin.skeleton.campaign.ReferralStatus
import dev.sumin.skeleton.campaigntest.CampaignWebTestApplication
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.account.AccountLabels
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class ReferralTestBeans {
    @Bean fun facts(): AccountFacts = AccountFacts { JOINED }
    @Bean fun labels(): AccountLabels = AccountLabels { ids -> ids.associateWith { "$it@example.com" } }

    companion object {
        val JOINED: Instant = Instant.now().minus(Duration.ofDays(1))
    }
}

/** 초대 HTTP: 사용자 현황이 내보내지 않는 것, 운영 승인 · 취소의 상태 코드와 기록, 이름은 account.read 에게만, 네트워크 배선 */
@SpringBootTest(classes = [CampaignWebTestApplication::class], properties = ["skeleton.campaign.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(CampaignTestBeans::class, ReferralTestBeans::class)
class ReferralWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: CampaignStore

    private val fake get() = store as FakeCampaignStore
    private val inviter = TestingAuthenticationToken("alice", "n/a", "ROLE_USER")
    private val invitee = TestingAuthenticationToken("bob", "n/a", "ROLE_USER")
    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val reader = TestingAuthenticationToken("acc_reader", "n/a", "ROLE_USER")
    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    @BeforeEach
    fun reset() {
        CampaignTestBeans.GRANTS.clear()
        CampaignTestBeans.GRANTS["acc_op"] = setOf("campaign.manage")
        CampaignTestBeans.GRANTS["acc_reader"] = setOf("campaign.manage", "account.read")
        CampaignTestBeans.ACTIONS.clear()
        fake.campaigns.clear()
        fake.referrals.clear()
        fake.codes.clear()
        fake.codeNetworks.clear()
    }

    private fun referral(invitee: String, status: ReferralStatus, minutes: Long = 0, flags: Set<ReferralFlag> = emptySet(), reason: String? = null) {
        fake.referrals[invitee] = Referral(
            invitee, "alice", "c1", status, t0.plusSeconds(minutes * 60), null,
            releaseAt = if (status == ReferralStatus.HELD) t0.plusSeconds(86400) else null,
            settledBy = reason?.let { "acc_op" }, cancelReason = reason, flags = flags, qualifyEvent = "first-payment",
        )
    }

    private fun startCampaign() {
        mvc.perform(
            post("/api/v1/admin/campaigns").principal(operator).contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"referral","name":"Invite","startsAt":"2026-01-01T00:00:00Z","config":{"qualifyOn":"SIGNAL","reviewSameNetwork":true,"invitee":[{"type":"point","params":{"amount":"1"}}],"inviter":[{"type":"point","params":{"amount":"1"}}]}}"""),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `me carries canClaim and claimUntil and the callers network reaches the same-network flag`() {
        startCampaign()
        val ip = { r: org.springframework.mock.web.MockHttpServletRequest -> r.remoteAddr = "10.1.2.3"; r }
        mvc.perform(get("/api/v1/referrals/me").principal(inviter).with(ip)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.canClaim").value(true))
            .andExpect(jsonPath("$.value.claimUntil").isNotEmpty)
        val code = fake.codes.getValue("alice")

        mvc.perform(post("/api/v1/referrals/claim").principal(invitee).with(ip).contentType(MediaType.APPLICATION_JSON).content("""{"code":"$code"}""")).andExpect(status().isNoContent)

        assertEquals(setOf(ReferralFlag.SAME_NETWORK), fake.referrals.getValue("bob").flags)
        mvc.perform(get("/api/v1/referrals/me").principal(invitee)).andExpect(jsonPath("$.value.canClaim").value(false))
    }

    @Test
    fun `my invitees show four user statuses, masked names and nothing an operator keeps to himself`() {
        referral("bob1", ReferralStatus.REVIEW, 1, setOf(ReferralFlag.SAME_NETWORK))
        referral("bob2", ReferralStatus.HELD, 2)
        referral("bob3", ReferralStatus.CANCELED, 3, reason = "refunded")
        referral("bob4", ReferralStatus.QUALIFIED, 4)
        referral("bob5", ReferralStatus.CLAIMED, 5)
        referral("bob6", ReferralStatus.CAPPED, 6)

        val body = mvc.perform(get("/api/v1/referrals/me/invitees").principal(inviter)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(6))
            .andExpect(jsonPath("$.values[0].status").value("NOT_REWARDED"))
            .andExpect(jsonPath("$.values[0].label").value("bo***"))
            .andExpect(jsonPath("$.values[?(@.status=='PENDING')].rewardAt").isNotEmpty)   // 보류 중만 보상 시각이 나간다
            .andReturn().response.contentAsString
        val statuses = Regex(""""status":"(\w+)"""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("WAITING", "PENDING", "REWARDED", "NOT_REWARDED"), statuses)
        listOf("REVIEW", "HELD", "SAME_NETWORK", "refunded", "example.com", "first-payment", "acc_op", "flags", "cancelReason").forEach { assertFalse(body.contains(it), "leaked $it") }
    }

    @Test
    fun `my invitees page by cursor and never show another inviters people`() {
        referral("bob1", ReferralStatus.CLAIMED, 1)
        referral("bob2", ReferralStatus.CLAIMED, 2)
        fake.referrals["eve1"] = fake.referrals.getValue("bob1").copy(inviteeId = "eve1", inviterId = "mallory")

        val first = mvc.perform(get("/api/v1/referrals/me/invitees?size=1").principal(inviter))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].label").value("bo***"))
            .andExpect(jsonPath("$.cursor.hasNext").value(true)).andReturn().response.contentAsString
        val cursor = Regex(""""nextCursor":"([^"]+)"""").find(first)!!.groupValues[1]
        mvc.perform(get("/api/v1/referrals/me/invitees?size=1&cursor=$cursor").principal(inviter))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.cursor.hasNext").value(false))
        mvc.perform(get("/api/v1/referrals/me/invitees?cursor=not-a-cursor").principal(inviter)).andExpect(status().isBadRequest)
    }

    @Test
    fun `the operator list names accounts only for an operator who also holds account read`() {
        referral("bob1", ReferralStatus.REVIEW, 1, setOf(ReferralFlag.SAME_NETWORK))
        mvc.perform(get("/api/v1/admin/referrals?status=REVIEW&flag=SAME_NETWORK").principal(reader)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].inviteeLabel").value("bob1@example.com"))
            .andExpect(jsonPath("$.values[0].flags[0]").value("SAME_NETWORK"))
        mvc.perform(get("/api/v1/admin/referrals").principal(operator)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].inviteeId").value("bob1"))
            .andExpect(jsonPath("$.values[0].inviteeLabel").doesNotExist())
        mvc.perform(get("/api/v1/admin/referrals").principal(inviter)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/referrals?status=NOPE").principal(operator)).andExpect(status().isBadRequest)
    }

    @Test
    fun `approve pays a pending one once and is 409 for anything else, audited`() {
        startCampaign()
        referral("bob1", ReferralStatus.REVIEW)
        fake.referrals["bob1"] = fake.referrals.getValue("bob1").copy(campaignId = fake.campaigns.values.first().id)
        CampaignTestBeans.ACTIONS.clear()

        mvc.perform(post("/api/v1/admin/referrals/bob1/approve").principal(operator)).andExpect(status().isNoContent)
        assertEquals(ReferralStatus.QUALIFIED, fake.referrals.getValue("bob1").status)
        assertEquals(listOf("campaign.referral.approved" to "bob1"), CampaignTestBeans.ACTIONS.map { it.action to it.targetId })
        mvc.perform(post("/api/v1/admin/referrals/bob1/approve").principal(operator)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CAMPAIGN.REFERRAL_NOT_PENDING"))
        mvc.perform(post("/api/v1/admin/referrals/ghost/approve").principal(operator)).andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/admin/referrals/bob1/approve").principal(inviter)).andExpect(status().isForbidden)
        assertEquals(1, CampaignTestBeans.ACTIONS.size)
    }

    @Test
    fun `cancel needs a reason, refuses a settled one, and is audited when it works`() {
        referral("bob1", ReferralStatus.HELD)
        referral("bob2", ReferralStatus.QUALIFIED)
        fun cancel(id: String, json: String) =
            mvc.perform(post("/api/v1/admin/referrals/$id/cancel").principal(operator).contentType(MediaType.APPLICATION_JSON).content(json))

        cancel("bob1", """{"reason":"  "}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CAMPAIGN.REFERRAL_REASON_INVALID"))
        cancel("bob1", """{}""").andExpect(status().isBadRequest)
        cancel("bob2", """{"reason":"fraud"}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CAMPAIGN.REFERRAL_NOT_CANCELABLE"))
        assertEquals(emptyList(), CampaignTestBeans.ACTIONS.map { it.action })

        cancel("bob1", """{"reason":"fraud"}""").andExpect(status().isNoContent)
        assertEquals(ReferralStatus.CANCELED, fake.referrals.getValue("bob1").status)
        assertEquals("fraud", fake.referrals.getValue("bob1").cancelReason)
        assertEquals(listOf("campaign.referral.canceled" to "bob1"), CampaignTestBeans.ACTIONS.map { it.action to it.targetId })
    }
}
