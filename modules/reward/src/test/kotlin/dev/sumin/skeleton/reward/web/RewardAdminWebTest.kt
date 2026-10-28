package dev.sumin.skeleton.reward.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.reward.FakeGrantStore
import dev.sumin.skeleton.reward.RecordingTasks
import dev.sumin.skeleton.reward.RewardGrantService
import dev.sumin.skeleton.reward.RewardGrantStatus
import dev.sumin.skeleton.reward.RewardGrants
import dev.sumin.skeleton.reward.RewardGrantStore
import dev.sumin.skeleton.reward.RewardTasks
import dev.sumin.skeleton.reward.ScriptedIssuer
import dev.sumin.skeleton.rewardtest.RewardWebTestApplication
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class RewardTestBeans {
    @Bean fun store() = FakeGrantStore()
    @Bean fun grantStore(s: FakeGrantStore): RewardGrantStore = s
    @Bean fun tasks(): RewardTasks = RecordingTasks()
    @Bean fun issuer(): RewardIssuer = ScriptedIssuer()
    @Bean fun grants(): GrantsHolder = GrantsHolder()
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }

    class GrantsHolder
    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
    }
}

/** 운영자 경로: `reward.operate` 가 없으면 403, 익명 401, 켜지 않으면 404, 다시 지급은 FAILED 만 */
@SpringBootTest(classes = [RewardWebTestApplication::class], properties = ["skeleton.reward.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(RewardTestBeans::class)
class RewardAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeGrantStore
    @Autowired lateinit var recorder: RewardGrants

    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")

    @BeforeEach
    fun reset() {
        store.rows.clear()
        RewardTestBeans.GRANTS.clear()
        RewardTestBeans.GRANTS["acc_op"] = setOf("reward.operate")
    }

    private fun failedGrant(): String {
        val id = recorder.record("acc_1", "point", mapOf("amount" to "5"), "attendance", "k${store.rows.size}")!!
        store.markFailed(id, "boom")
        return id
    }

    @Test
    fun `a caller without reward operate gets 403 and an anonymous caller 401`() {
        mvc.perform(get("/api/v1/admin/reward-grants").principal(other)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/reward-grants")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/admin/reward-grants/x/retry").principal(other)).andExpect(status().isForbidden)
    }

    @Test
    fun `an operator lists grants newest first with the issuer label`() {
        val id = failedGrant()
        mvc.perform(get("/api/v1/admin/reward-grants?status=failed").principal(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].id").value(id))
            .andExpect(jsonPath("$.values[0].status").value("FAILED"))
            .andExpect(jsonPath("$.values[0].label").value("Points"))
            .andExpect(jsonPath("$.values[0].lastError").value("boom"))
    }

    @Test
    fun `retry only takes a failed grant, anything else is 409 NOT_RETRYABLE`() {
        val failed = failedGrant()
        mvc.perform(post("/api/v1/admin/reward-grants/$failed/retry").principal(operator)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.id").value(failed)).andExpect(jsonPath("$.value.status").value("PENDING"))
        assertEquals(RewardGrantStatus.PENDING, store.find(failed)!!.status)

        mvc.perform(post("/api/v1/admin/reward-grants/$failed/retry").principal(operator))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REWARD.NOT_RETRYABLE"))
        mvc.perform(post("/api/v1/admin/reward-grants/nope/retry").principal(operator)).andExpect(status().isNotFound)
    }
}

@SpringBootTest(classes = [RewardWebTestApplication::class])
@AutoConfigureMockMvc
@Import(RewardTestBeans::class)
class RewardAdminDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the operator paths do not exist unless enabled`() {
        RewardTestBeans.GRANTS["acc_op"] = setOf("*")
        mvc.perform(get("/api/v1/admin/reward-grants").principal(TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}
