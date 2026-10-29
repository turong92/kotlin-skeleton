package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.coupon.Coupon
import dev.sumin.skeleton.coupon.CouponDefinitionStatus
import dev.sumin.skeleton.coupon.CouponSource
import dev.sumin.skeleton.coupon.CouponStatus
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
    @Bean fun labels(): AccountLabels = AccountLabels { ids -> ids.filter { it == "acc_5" }.associateWith { "five@example.com" } }

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
    fun `the window and the use start round trip, a bad window is a 400, and a closed window is a 409 ISSUE_CLOSED`() {
        val past = "2020-01-01T00:00:00Z"
        val future = "2999-01-01T00:00:00Z"
        mvc.perform(
            post("/api/v1/admin/coupon-definitions").principal(operator).contentType(json)
                .content("""{"name":"window","stages":[{"type":"FREE","periods":1}],"issueStartsAt":"$future","issueEndsAt":"2999-02-01T00:00:00Z","validFrom":"$future"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.issueStartsAt").value(future))
            .andExpect(jsonPath("$.value.issueEndsAt").value("2999-02-01T00:00:00Z"))
            .andExpect(jsonPath("$.value.validFrom").value(future))
        val id = store.definitions.keys.single()
        mvc.perform(post("/api/v1/admin/coupon-definitions/$id/issue").principal(operator).contentType(json).content("""{"accountIds":["acc_1"]}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUPON.ISSUE_CLOSED"))
        assertEquals(0, CouponTestBeans.AUDIT.size)

        mvc.perform(
            post("/api/v1/admin/coupon-definitions").principal(operator).contentType(json)
                .content("""{"name":"bad","stages":[{"type":"FREE","periods":1}],"issueStartsAt":"$future","issueEndsAt":"$past"}"""),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COUPON.DEFINITION_INVALID"))
        mvc.perform(
            post("/api/v1/admin/coupon-definitions").principal(operator).contentType(json)
                .content("""{"name":"bad","stages":[{"type":"FREE","periods":1}],"validFrom":"$future","validUntil":"$past"}"""),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COUPON.DEFINITION_INVALID"))
    }

    @Test
    fun `a coupon carries usableFrom so the wallet can say from when it can be used`() {
        val start = "2999-01-01T00:00:00Z"
        val body = mvc.perform(
            post("/api/v1/admin/coupon-definitions").principal(operator).contentType(json)
                .content("""{"name":"later","stages":[{"type":"FREE","periods":1}],"validFrom":"$start"}"""),
        ).andReturn().response.contentAsString
        val id = Regex(""""id":"(cpd_[^"]+)"""").find(body)!!.groupValues[1]
        mvc.perform(post("/api/v1/admin/coupon-definitions/$id/issue").principal(operator).contentType(json).content("""{"accountIds":["acc_1"]}"""))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/coupons").principal(user))
            .andExpect(jsonPath("$.values[0].status").value("AVAILABLE"))
            .andExpect(jsonPath("$.values[0].usableFrom").value(start))
        mvc.perform(get("/api/v1/admin/coupons?accountId=acc_1").principal(operator)).andExpect(jsonPath("$.values[0].usableFrom").value(start))
    }

    @Test
    fun `stats count the issued coupons by status with the discount totals, and the list pages newest first with a status filter`() {
        val id = createFreePass()
        val definition = store.definitions.getValue(id)
        val now = java.time.Instant.now()
        fun put(n: Int, status: CouponStatus, holder: String? = null, expiresAt: java.time.Instant? = null) {
            store.coupons["cpn_$n"] = Coupon("cpn_$n", id, "acc_$n", status, holder, CouponSource.ADMIN, "r$n", expiresAt, if (status == CouponStatus.USED) now else null, now, now)
        }
        put(1, CouponStatus.AVAILABLE)
        put(2, CouponStatus.AVAILABLE, expiresAt = now.minusSeconds(60))
        put(3, CouponStatus.RESERVED, holder = "ord_3")
        put(4, CouponStatus.USED, holder = "ord_4")
        put(5, CouponStatus.USED, holder = "ord_5")
        put(6, CouponStatus.REVOKED, holder = "ord_old")
        assertEquals(CouponDefinitionStatus.ACTIVE, definition.status)

        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/stats").principal(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.issued").value(6))
            .andExpect(jsonPath("$.value.available").value(1))
            .andExpect(jsonPath("$.value.reserved").value(1))
            .andExpect(jsonPath("$.value.used").value(2))
            .andExpect(jsonPath("$.value.expired").value(1))
            .andExpect(jsonPath("$.value.revoked").value(1))
            .andExpect(jsonPath("$.value.discountTotals.length()").value(0))   // 결제 장부(DiscountTotals 빈)가 없는 조립 - 모르니 빈 배열

        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/coupons?size=4").principal(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(4))
            .andExpect(jsonPath("$.values[0].id").value("cpn_6"))
            .andExpect(jsonPath("$.values[0].orderRef").doesNotExist())
            .andExpect(jsonPath("$.cursor.hasNext").value(true))
            .andExpect(jsonPath("$.cursor.nextCursor").value("cpn_3"))
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/coupons?size=4&cursor=cpn_3").principal(operator))
            .andExpect(jsonPath("$.values.length()").value(2)).andExpect(jsonPath("$.cursor.hasNext").value(false))
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/coupons?status=used").principal(operator))
            .andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.values[0].orderRef").value("ord_5"))
            .andExpect(jsonPath("$.values[0].usedAt").exists())
            .andExpect(jsonPath("$.values[0].accountId").value("acc_5"))
            .andExpect(jsonPath("$.values[0].accountLabel").value("five@example.com"))
            .andExpect(jsonPath("$.values[1].accountLabel").doesNotExist())   // 이름을 모르는 계정은 칸이 비고 화면이 id 를 보인다
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/coupons?status=EXPIRED").principal(operator))
            .andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].id").value("cpn_2"))
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/coupons?status=BOGUS").principal(operator)).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/admin/coupon-definitions/cpd_none/stats").principal(operator)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/admin/coupon-definitions/cpd_none/coupons").principal(operator)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/stats").principal(other)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/coupons").principal(other)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/coupon-definitions/$id/stats")).andExpect(status().isUnauthorized)
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
