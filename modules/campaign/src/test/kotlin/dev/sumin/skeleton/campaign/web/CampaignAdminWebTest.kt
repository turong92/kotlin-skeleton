package dev.sumin.skeleton.campaign.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.FakeCampaignStore
import dev.sumin.skeleton.campaign.PointIssuer
import dev.sumin.skeleton.campaign.RecordingGrants
import dev.sumin.skeleton.campaigntest.CampaignWebTestApplication
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.reward.RewardGrants
import java.util.concurrent.ConcurrentHashMap
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
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class CampaignTestBeans {
    @Bean fun store(): CampaignStore = FakeCampaignStore()
    @Bean fun grants(): RewardGrants = RecordingGrants()
    @Bean fun issuer(): RewardIssuer = PointIssuer()
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { ACTIONS += it }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val ACTIONS = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 운영자 경로: 권한 · 스위치 · 모르는 보상 타입 400 · 켜고 끄기의 운영 기록. 사용자 경로의 응답 꼴(C-R) */
@SpringBootTest(classes = [CampaignWebTestApplication::class], properties = ["skeleton.campaign.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(CampaignTestBeans::class)
class CampaignAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: CampaignStore

    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")
    private val body = """{"kind":"attendance","name":"Daily","startsAt":"2026-01-01T00:00:00Z","endsAt":null,"config":{"daily":[{"type":"point","params":{"amount":"10"}}]}}"""

    @BeforeEach
    fun reset() {
        CampaignTestBeans.GRANTS.clear()
        CampaignTestBeans.GRANTS["acc_op"] = setOf("campaign.manage")
        CampaignTestBeans.ACTIONS.clear()
        (store as FakeCampaignStore).campaigns.clear()   // 컨텍스트가 시험 사이에 캐시된다
    }

    private fun create(json: String = body) =
        mvc.perform(post("/api/v1/admin/campaigns").principal(operator).contentType(MediaType.APPLICATION_JSON).content(json))

    @Test
    fun `without campaign manage 403, anonymous 401`() {
        mvc.perform(get("/api/v1/admin/campaigns").principal(other)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/campaigns")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/admin/campaigns").principal(other).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
    }

    @Test
    fun `an operator creates and lists a campaign in the contract shape`() {
        create().andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.kind").value("attendance"))
            .andExpect(jsonPath("$.value.enabled").value(true))
            .andExpect(jsonPath("$.value.config.daily[0].params.amount").value("10"))
        mvc.perform(get("/api/v1/admin/campaigns").principal(operator)).andExpect(status().isOk).andExpect(jsonPath("$.values[0].name").value("Daily"))
        assertEquals(listOf("campaign.created"), CampaignTestBeans.ACTIONS.map { it.action })
    }

    @Test
    fun `an unknown reward type or a bad setting is 400 CAMPAIGN_CONFIG_INVALID`() {
        create(body.replace(""""type":"point"""", """"type":"badge"""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CAMPAIGN.CONFIG_INVALID"))
        create(body.replace(""""amount":"10"""", """"amount":"-1"""")).andExpect(status().isBadRequest)
        create(body.replace("attendance", "mystery")).andExpect(status().isBadRequest)
        create("""{"kind":"referral","name":"Invite","startsAt":"2026-01-01T00:00:00Z","config":{"invitee":[{"type":"point","params":{"amount":"1"}}]}}""")
            .andExpect(status().isBadRequest)   // qualifyOn 은 설정이 정해야 한다
    }

    @Test
    fun `toggling enabled is audited as campaign updated and ends the check-in`() {
        val id = JsonPath.read<String>(create().andReturn().response.contentAsString, "$.value.id")
        val user = TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")
        mvc.perform(post("/api/v1/campaigns/$id/attendance").principal(user)).andExpect(status().isOk).andExpect(jsonPath("$.value.alreadyChecked").value(false))

        mvc.perform(patch("/api/v1/admin/campaigns/$id/enabled").principal(operator).contentType(MediaType.APPLICATION_JSON).content("""{"enabled":false}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.enabled").value(false))
        mvc.perform(post("/api/v1/campaigns/$id/attendance").principal(user)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CAMPAIGN.NOT_ACTIVE"))
        mvc.perform(get("/api/v1/campaigns").principal(user)).andExpect(jsonPath("$.values.length()").value(0))

        assertEquals(listOf("campaign.created", "campaign.updated"), CampaignTestBeans.ACTIONS.map { it.action })
    }

    @Test
    fun `the active list carries my attendance summary and anonymous is refused`() {
        val id = JsonPath.read<String>(create().andReturn().response.contentAsString, "$.value.id")
        val user = TestingAuthenticationToken("acc_2", "n/a", "ROLE_USER")
        mvc.perform(post("/api/v1/campaigns/$id/attendance").principal(user)).andExpect(status().isOk).andExpect(jsonPath("$.value.rewards[0].type").value("point"))
        mvc.perform(get("/api/v1/campaigns").principal(user))
            .andExpect(jsonPath("$.values[0].attendance.checkedToday").value(true))
            .andExpect(jsonPath("$.values[0].attendance.total").value(1))
            .andExpect(jsonPath("$.values[0].attendance.nextMilestone").doesNotExist())
        mvc.perform(get("/api/v1/campaigns")).andExpect(status().isUnauthorized)
    }
}

@SpringBootTest(classes = [CampaignWebTestApplication::class])
@AutoConfigureMockMvc
@Import(CampaignTestBeans::class)
class CampaignAdminDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the operator paths do not exist unless enabled`() {
        CampaignTestBeans.GRANTS["acc_op"] = setOf("*")
        mvc.perform(get("/api/v1/admin/campaigns").principal(TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}
