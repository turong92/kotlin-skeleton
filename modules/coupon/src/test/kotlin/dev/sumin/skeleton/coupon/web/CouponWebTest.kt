package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.coupon.CouponStore
import dev.sumin.skeleton.coupon.FakeCouponStore
import dev.sumin.skeleton.coupontest.CouponWebTestApplication
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
class CouponTestBeans {
    @Bean fun store() = FakeCouponStore()
    @Bean fun couponStore(s: FakeCouponStore): CouponStore = s
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { AUDIT += it }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val AUDIT = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 운영자 경로: 권한 · 정의 만들기(「무료 이용권」 틀 `[FREE×1]`) · 발급의 운영 기록 · 공용 코드를 사용자가 등록 · 회수 */
@SpringBootTest(classes = [CouponWebTestApplication::class], properties = ["skeleton.coupon.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(CouponTestBeans::class)
class CouponAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeCouponStore

    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")
    private val user = TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")
    private val json = MediaType.APPLICATION_JSON

    @BeforeEach
    fun reset() {
        store.definitions.clear()
        store.coupons.clear()
        store.codes.clear()
        store.redemptions.clear()
        CouponTestBeans.GRANTS.clear()
        CouponTestBeans.GRANTS["acc_op"] = setOf("coupon.manage")
        CouponTestBeans.AUDIT.clear()
    }

    private fun createFreePass(): String {
        val body = mvc.perform(
            post("/api/v1/admin/coupon-definitions").principal(operator).contentType(json)
                .content("""{"name":"무료 이용권","stages":[{"type":"FREE","periods":1}],"scope":{"purposes":["ONE_TIME"],"planCodes":[]},"perAccountLimit":3}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.name").value("무료 이용권"))
            .andExpect(jsonPath("$.value.status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.stages.length()").value(1))
            .andExpect(jsonPath("$.value.stages[0].type").value("FREE"))
            .andExpect(jsonPath("$.value.stages[0].periods").value(1))
            .andExpect(jsonPath("$.value.scope.purposes[0]").value("ONE_TIME"))
            .andExpect(jsonPath("$.value.issuedCount").value(0))
            .andReturn().response.contentAsString
        return Regex(""""id":"(cpd_[^"]+)"""").find(body)!!.groupValues[1]
    }

    @Test
    fun `a caller without coupon manage gets 403 and an anonymous caller 401`() {
        mvc.perform(get("/api/v1/admin/coupon-definitions").principal(other)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/coupon-definitions")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/admin/coupon-definitions/x/issue").principal(other).contentType(json).content("""{"accountIds":["a"]}""")).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/coupons?accountId=acc_1").principal(other)).andExpect(status().isForbidden)
    }

    @Test
    fun `a bad stage list is a 400 COUPON DEFINITION_INVALID`() {
        mvc.perform(
            post("/api/v1/admin/coupon-definitions").principal(operator).contentType(json)
                .content("""{"name":"bad","stages":[{"type":"PERCENT","value":101,"periods":1}]}"""),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COUPON.DEFINITION_INVALID"))
        assertEquals(0, store.definitions.size)
    }

    @Test
    fun `issuing records coupon issued and gives each account one coupon`() {
        val id = createFreePass()
        mvc.perform(
            post("/api/v1/admin/coupon-definitions/$id/issue").principal(operator).contentType(json).content("""{"accountIds":["acc_1","acc_2","acc_1"]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.issued.length()").value(2))
            .andExpect(jsonPath("$.value.skipped.length()").value(0))

        val recorded = CouponTestBeans.AUDIT.single()
        assertEquals("coupon.issued", recorded.action)
        assertEquals("acc_op", recorded.actorId)
        assertEquals(id, recorded.targetId)

        mvc.perform(get("/api/v1/admin/coupons?accountId=acc_1").principal(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].accountId").value("acc_1"))
            .andExpect(jsonPath("$.values[0].status").value("AVAILABLE"))
        mvc.perform(get("/api/v1/admin/coupon-definitions").principal(operator))
            .andExpect(jsonPath("$.values[0].issuedCount").value(2))
    }

    @Test
    fun `a paused definition stops issuing with 409`() {
        val id = createFreePass()
        mvc.perform(patch("/api/v1/admin/coupon-definitions/$id/status").principal(operator).contentType(json).content("""{"status":"PAUSED"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("PAUSED"))
        mvc.perform(post("/api/v1/admin/coupon-definitions/$id/issue").principal(operator).contentType(json).content("""{"accountIds":["acc_1"]}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON.DEFINITION_NOT_ACTIVE"))
        assertEquals(0, CouponTestBeans.AUDIT.size)
    }

    @Test
    fun `a shared code lands in the wallet once per account and an available coupon can be revoked`() {
        val id = createFreePass()
        val code = Regex(""""code":"([^"]+)"""").find(
            mvc.perform(post("/api/v1/admin/coupon-definitions/$id/codes").principal(operator)).andExpect(status().isCreated)
                .andReturn().response.contentAsString,
        )!!.groupValues[1]

        mvc.perform(post("/api/v1/coupons/redeem").principal(user).contentType(json).content("""{"code":"${code.lowercase()}"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.name").value("무료 이용권"))
            .andExpect(jsonPath("$.value.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.value.stages[0].type").value("FREE"))
        mvc.perform(post("/api/v1/coupons/redeem").principal(user).contentType(json).content("""{"code":"$code"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON.CODE_USED"))
        mvc.perform(post("/api/v1/coupons/redeem").principal(user).contentType(json).content("""{"code":"NOPE-NOPE"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COUPON.CODE_INVALID"))
        mvc.perform(post("/api/v1/coupons/redeem").contentType(json).content("""{"code":"$code"}""")).andExpect(status().isUnauthorized)

        val listed = mvc.perform(get("/api/v1/coupons?status=AVAILABLE").principal(user)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(1)).andReturn().response.contentAsString
        val couponId = Regex(""""id":"(cpn_[^"]+)"""").find(listed)!!.groupValues[1]
        mvc.perform(get("/api/v1/coupons?status=PAST").principal(user)).andExpect(jsonPath("$.values.length()").value(0))

        mvc.perform(post("/api/v1/admin/coupons/$couponId/revoke").principal(operator)).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("REVOKED"))
        mvc.perform(post("/api/v1/admin/coupons/$couponId/revoke").principal(operator)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON.NOT_REVOCABLE"))
        mvc.perform(get("/api/v1/coupons?status=PAST").principal(user)).andExpect(jsonPath("$.values.length()").value(1))
    }
}

@SpringBootTest(classes = [CouponWebTestApplication::class])
@AutoConfigureMockMvc
@Import(CouponTestBeans::class)
class CouponAdminDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the operator paths do not exist unless enabled`() {
        CouponTestBeans.GRANTS["acc_op"] = setOf("*")
        mvc.perform(get("/api/v1/admin/coupon-definitions").principal(TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}
