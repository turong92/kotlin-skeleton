package dev.sumin.skeleton.badge.web

import dev.sumin.skeleton.badge.BadgeAwards
import dev.sumin.skeleton.badge.BadgeDefinitionInput
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.badge.BadgeCatalog
import dev.sumin.skeleton.badge.BadgeStatus
import dev.sumin.skeleton.badge.BadgeStore
import dev.sumin.skeleton.badge.FakeBadgeStore
import dev.sumin.skeleton.badgetest.BadgeWebTestApplication
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

@TestConfiguration(proxyBeanMethods = false)
class BadgeTestBeans {
    @Bean fun store() = FakeBadgeStore()
    @Bean fun badgeStore(s: FakeBadgeStore): BadgeStore = s
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { AUDIT += it }
    @Bean fun labels(): AccountLabels = AccountLabels { ids -> ids.filter { it == "acc_1" }.associateWith { "one@example.com" } }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val AUDIT = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 사용자 경로 - 내 배지(숨은 정의 가림 · 본인만 스위치)와 로그인 없이 읽는 프로필 배지(숨긴 것 · 받지 않은 숨은 것은 어떤 문자열로도 안 나간다) */
@SpringBootTest(classes = [BadgeWebTestApplication::class])
@AutoConfigureMockMvc
@Import(BadgeTestBeans::class)
class BadgeUserWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeBadgeStore
    @Autowired lateinit var definitions: BadgeDefinitions
    @Autowired lateinit var awards: BadgeAwards
    @Autowired lateinit var catalog: BadgeCatalog
    @Autowired lateinit var contributors: List<PublicEndpointContributor>
    @Autowired lateinit var mapper: ObjectMapper

    private val user = TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_2", "n/a", "ROLE_USER")

    @BeforeEach
    fun seed() {
        store.clear()
        fun def(code: String, name: String, hidden: Boolean = false, order: Int = 0) =
            definitions.create(BadgeDefinitionInput(code, name, "about $name", "https://cdn.example.com/$code.png", hidden, order))
        def("shown", "Shown Badge", order = 1)
        def("tucked", "Tucked Away Badge", order = 2)
        def("secret-held", "Secret Held Badge", hidden = true, order = 3)
        def("secret-unearned", "Secret Unearned Badge", hidden = true, order = 4)
        def("revoked", "Revoked Badge", order = 5)
        def("plain-unearned", "Plain Unearned Badge", order = 6)
        awards.award("acc_1", "shown", "r")
        val tucked = awards.award("acc_1", "tucked", "r")
        awards.award("acc_1", "secret-held", "r")
        awards.award("acc_1", "revoked", "r")
        awards.revoke("acc_1", "revoked", "oops")
        catalog.setVisible("acc_1", tucked.awardId, false)
    }

    @Test
    fun `the profile is readable without a login and carries exactly four keys per badge`() {
        val body = mvc.perform(get("/api/v1/badges/accounts/acc_1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.badges.length()").value(2))
            .andExpect(jsonPath("$.value.badges[0].code").value("shown"))
            .andExpect(jsonPath("$.value.badges[1].code").value("secret-held"))   // 받은 숨은 업적은 본인이 안 숨겼다면 보인다
            .andReturn().response.contentAsString
        val badge = mapper.readTree(body).path("value").path("badges").get(0)
        assertEquals(setOf("code", "name", "description", "imageUrl"), badge.propertyNames().asSequence().toSet())
    }

    @Test
    fun `a hidden, revoked or unearned secret badge never appears in any string of the public response`() {
        val body = mvc.perform(get("/api/v1/badges/accounts/acc_1")).andReturn().response.contentAsString
        listOf("tucked", "Tucked", "revoked", "Revoked", "secret-unearned", "Secret Unearned", "plain-unearned", "Plain Unearned", "bda_", "awardId", "awardedAt").forEach {
            assertFalse(body.contains(it), "public response must not contain '$it': $body")
        }
    }

    @Test
    fun `an unknown account, an account without badges and an account that hid everything answer the same`() {
        val hider = awards.award("acc_4", "shown", "r")
        catalog.setVisible("acc_4", hider.awardId, false)
        fun value(id: String) = mapper.readTree(mvc.perform(get("/api/v1/badges/accounts/$id")).andExpect(status().isOk).andReturn().response.contentAsString).path("value").toString()
        assertEquals("""{"badges":[]}""", value("acc_unknown"))
        assertEquals(value("acc_unknown"), value("acc_3"))
        assertEquals(value("acc_unknown"), value("acc_4"))
    }

    @Test
    fun `only the profile path is declared public, not the personal one`() {
        val registry = PublicEndpointRegistry.from(WebProperties(), contributors)
        val badgePaths = registry.endpoints.filter { it.pattern.startsWith("/api/v1/badges") }
        assertEquals(listOf(HttpMethod.GET to "/api/v1/badges/accounts/*"), badgePaths.map { it.method to it.pattern })
    }

    @Test
    fun `my badges need a login and mask a locked secret definition completely`() {
        mvc.perform(get("/api/v1/badges/me")).andExpect(status().isUnauthorized)
        val body = mvc.perform(get("/api/v1/badges/me").principal(user))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.earned.length()").value(3))
            .andExpect(jsonPath("$.value.locked.length()").value(3))   // 회수된 배지는 다시 받을 수 있어 아직인 것이다
            .andReturn().response.contentAsString
        assertFalse(body.contains("secret-unearned") || body.contains("Secret Unearned"), body)
        assertTrue(body.contains("plain-unearned"))
        val locked = mapper.readTree(body).path("value").path("locked")
        val secret = locked.first { it.path("hidden").asBoolean() }
        assertTrue(secret.path("code").isNull && secret.path("name").isNull && secret.path("description").isNull && secret.path("imageUrl").isNull)
    }

    @Test
    fun `only the owner flips visibility and a stranger's award id is a 404`() {
        val awardId = store.awards.values.first { it.accountId == "acc_1" && it.visible }.id
        mvc.perform(patch("/api/v1/badges/me/$awardId").principal(other).contentType(MediaType.APPLICATION_JSON).content("""{"visible":false}"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("BADGE.NOT_FOUND"))
        mvc.perform(patch("/api/v1/badges/me/$awardId").contentType(MediaType.APPLICATION_JSON).content("""{"visible":false}""")).andExpect(status().isUnauthorized)
        mvc.perform(patch("/api/v1/badges/me/$awardId").principal(user).contentType(MediaType.APPLICATION_JSON).content("""{}""")).andExpect(status().isBadRequest)
        mvc.perform(patch("/api/v1/badges/me/$awardId").principal(user).contentType(MediaType.APPLICATION_JSON).content("""{"visible":false}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.visible").value(false))
        mvc.perform(get("/api/v1/badges/accounts/acc_1")).andExpect(jsonPath("$.value.badges.length()").value(1))
    }
}

@SpringBootTest(classes = [BadgeWebTestApplication::class], properties = ["skeleton.badge.http.allow-anonymous-read=false"])
@AutoConfigureMockMvc
@Import(BadgeTestBeans::class)
class BadgeProfileClosedWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var contributors: ObjectProvider<PublicEndpointContributor>

    @Test
    fun `a closed profile asks for a login and declares no public path`() {
        mvc.perform(get("/api/v1/badges/accounts/acc_1")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/badges/accounts/acc_1").principal(TestingAuthenticationToken("acc_9", "n/a", "ROLE_USER"))).andExpect(status().isOk)
        assertTrue(PublicEndpointRegistry.from(WebProperties(), contributors.orderedStream().toList()).endpoints.none { it.pattern.startsWith("/api/v1/badges") })
    }
}

@SpringBootTest(classes = [BadgeWebTestApplication::class], properties = ["skeleton.badge.http.enabled=false"])
@AutoConfigureMockMvc
@Import(BadgeTestBeans::class)
class BadgeHttpOffWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `switching http off removes the user paths`() {
        mvc.perform(get("/api/v1/badges/accounts/acc_1")).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/badges/me").principal(TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}

/** 운영자 경로: 권한 · 정의 · 수동 수여 · 회수(사유 필수) · 내역(계정 이름은 account.read 도 있을 때만) */
@SpringBootTest(classes = [BadgeWebTestApplication::class], properties = ["skeleton.badge.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(BadgeTestBeans::class)
class BadgeAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeBadgeStore

    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")
    private val json = MediaType.APPLICATION_JSON

    @BeforeEach
    fun reset() {
        store.clear()
        BadgeTestBeans.GRANTS.clear()
        BadgeTestBeans.GRANTS["acc_op"] = setOf("badge.manage")
        BadgeTestBeans.AUDIT.clear()
    }

    private fun create(code: String = "early-bird"): String {
        val body = mvc.perform(
            post("/api/v1/admin/badges").principal(operator).contentType(json)
                .content("""{"code":"$code","name":"Early bird","description":"first week","imageUrl":"https://cdn.example.com/eb.png","sortOrder":3}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.code").value(code))
            .andExpect(jsonPath("$.value.status").value("ACTIVE"))
            .andExpect(jsonPath("$.value.holders").value(0))
            .andReturn().response.contentAsString
        return Regex(""""id":"(bdg_[^"]+)"""").find(body)!!.groupValues[1]
    }

    @Test
    fun `a caller without badge manage gets 403 and an anonymous caller 401`() {
        mvc.perform(get("/api/v1/admin/badges").principal(other)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/badges")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/admin/badges/x/awards").principal(other).contentType(json).content("""{"accountIds":["a"]}""")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin/badge-awards/x/revoke").principal(other).contentType(json).content("""{"reason":"r"}""")).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/badge-awards").principal(other)).andExpect(status().isForbidden)
    }

    @Test
    fun `define, edit without touching the code, archive, and a taken or malformed code is refused`() {
        val id = create()
        assertEquals(listOf("badge.defined"), BadgeTestBeans.AUDIT.map { it.action })
        mvc.perform(post("/api/v1/admin/badges").principal(operator).contentType(json).content("""{"code":"early-bird","name":"again"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BADGE.CODE_TAKEN"))
        mvc.perform(post("/api/v1/admin/badges").principal(operator).contentType(json).content("""{"code":"Bad Code","name":"x"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BADGE.CONFIG_INVALID"))
        mvc.perform(put("/api/v1/admin/badges/$id").principal(operator).contentType(json).content("""{"code":"changed","name":"Renamed","hidden":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.code").value("early-bird")).andExpect(jsonPath("$.value.name").value("Renamed")).andExpect(jsonPath("$.value.hidden").value(true))
        mvc.perform(patch("/api/v1/admin/badges/$id/status").principal(operator).contentType(json).content("""{"status":"ARCHIVED"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("ARCHIVED"))
        mvc.perform(post("/api/v1/admin/badges/$id/awards").principal(operator).contentType(json).content("""{"accountIds":["acc_1"]}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BADGE.ARCHIVED"))
        assertEquals(BadgeStatus.ARCHIVED, store.definitions.getValue(id).status)
        mvc.perform(put("/api/v1/admin/badges/bdg_none").principal(operator).contentType(json).content("""{"name":"x"}""")).andExpect(status().isNotFound)
    }

    @Test
    fun `a manual award reports awarded and alreadyHeld, then the revoke needs a reason and leaves a history line`() {
        val id = create()
        mvc.perform(post("/api/v1/admin/badges/$id/awards").principal(operator).contentType(json).content("""{"accountIds":["acc_1","acc_2"],"reason":"launch week"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.awarded.length()").value(2)).andExpect(jsonPath("$.value.alreadyHeld.length()").value(0))
        mvc.perform(post("/api/v1/admin/badges/$id/awards").principal(operator).contentType(json).content("""{"accountIds":["acc_2","acc_3"]}"""))
            .andExpect(jsonPath("$.value.awarded[0]").value("acc_3")).andExpect(jsonPath("$.value.alreadyHeld[0]").value("acc_2"))
        mvc.perform(post("/api/v1/admin/badges/$id/awards").principal(operator).contentType(json).content("""{"accountIds":[]}""")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/admin/badges").principal(operator)).andExpect(jsonPath("$.values[0].holders").value(3))

        val awardId = store.awards.values.first { it.accountId == "acc_1" }.id
        mvc.perform(post("/api/v1/admin/badge-awards/$awardId/revoke").principal(operator).contentType(json).content("""{"reason":"  "}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/admin/badge-awards/$awardId/revoke").principal(operator).contentType(json).content("""{}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/admin/badge-awards/$awardId/revoke").principal(operator).contentType(json).content("""{"reason":"abuse"}""")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/admin/badge-awards/$awardId/revoke").principal(operator).contentType(json).content("""{"reason":"abuse"}""")).andExpect(status().isNotFound)
        assertEquals(listOf("badge.defined", "badge.awarded", "badge.awarded", "badge.revoked"), BadgeTestBeans.AUDIT.map { it.action })
        assertEquals(setOf("badge", "badge-award"), BadgeTestBeans.AUDIT.map { it.targetType }.toSet())

        mvc.perform(get("/api/v1/admin/badge-awards?size=2").principal(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(2))
            .andExpect(jsonPath("$.values[0].action").value("REVOKED"))
            .andExpect(jsonPath("$.values[0].reason").value("abuse"))
            .andExpect(jsonPath("$.values[0].actorId").value("acc_op"))
            .andExpect(jsonPath("$.values[0].badgeCode").value("early-bird"))
            .andExpect(jsonPath("$.cursor.hasNext").value(true))
        mvc.perform(get("/api/v1/admin/badge-awards?accountId=acc_1").principal(operator)).andExpect(jsonPath("$.values.length()").value(2))
    }

    @Test
    fun `the account label in the history needs account read too`() {
        val id = create()
        mvc.perform(post("/api/v1/admin/badges/$id/awards").principal(operator).contentType(json).content("""{"accountIds":["acc_1"]}"""))
        mvc.perform(get("/api/v1/admin/badge-awards").principal(operator)).andExpect(jsonPath("$.values[0].accountLabel").doesNotExist())
        BadgeTestBeans.GRANTS["acc_op"] = setOf("badge.manage", "account.read")
        mvc.perform(get("/api/v1/admin/badge-awards").principal(operator)).andExpect(jsonPath("$.values[0].accountLabel").value("one@example.com"))
    }
}

@SpringBootTest(classes = [BadgeWebTestApplication::class])
@AutoConfigureMockMvc
@Import(BadgeTestBeans::class)
class BadgeAdminDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the operator paths do not exist unless enabled`() {
        BadgeTestBeans.GRANTS["acc_op"] = setOf("*")
        mvc.perform(get("/api/v1/admin/badges").principal(TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}
