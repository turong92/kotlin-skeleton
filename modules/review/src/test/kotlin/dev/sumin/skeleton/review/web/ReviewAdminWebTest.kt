package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.review.FakeReviewStore
import dev.sumin.skeleton.review.ReviewStatus
import dev.sumin.skeleton.reviewtest.ReviewWebTestApplication
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class ReviewAdminTestBeans {
    @Bean fun fakeStore() = FakeReviewStore()
    @Bean fun policy(): dev.sumin.skeleton.review.ReviewPolicy = object : dev.sumin.skeleton.review.ReviewPolicy { override fun canAttach(accountId: String, keys: List<String>) = true }
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { AUDIT += it }
    @Bean fun labels(): AccountLabels = AccountLabels { ids -> ids.associateWith { "$it@example.com" } }
    @Bean fun uploads(): PrivateUploadAccess = PrivateUploadAccess { key, _ -> "https://files.example.com/signed/$key?sig=abc" }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val AUDIT = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 운영 경로가 열려 있을 때 - 권한 · 큐 · 숨김과 되살리기 · 답글 · 운영 기록, 그리고 사진 302 */
@SpringBootTest(classes = [ReviewWebTestApplication::class], properties = ["skeleton.review.target-types=product", "skeleton.review.admin.enabled=true", "skeleton.review.max-photos=3"])
@AutoConfigureMockMvc
@Import(ReviewAdminTestBeans::class)
class ReviewAdminWebTest : ReviewWebBase() {
    private val mod = TestingAuthenticationToken("acc_mod_secret", "n/a", "ROLE_MODERATOR")
    private val boss = TestingAuthenticationToken("acc_boss_secret", "n/a", "ROLE_ADMIN")
    private val admin = "/api/v1/admin/reviews"

    @org.junit.jupiter.api.BeforeEach
    fun grants() {
        ReviewAdminTestBeans.GRANTS.clear()
        ReviewAdminTestBeans.AUDIT.clear()
        ReviewAdminTestBeans.GRANTS["acc_mod_secret"] = setOf("review.moderate")
        ReviewAdminTestBeans.GRANTS["acc_boss_secret"] = setOf("*")
    }

    private fun json(body: String) = MediaType.APPLICATION_JSON to body

    private fun report(who: TestingAuthenticationToken, id: String, reason: String = "SPAM") =
        mvc.perform(post("$BASE_PATH/$id/reports").principal(who).contentType(MediaType.APPLICATION_JSON).content("""{"reason":"$reason","detail":"seems off"}"""))

    @Test
    fun `reporting answers 204 once per account and refuses your own review`() {
        val id = write(alice)
        report(bob, id).andExpect(status().isNoContent)
        report(bob, id, "ABUSE").andExpect(status().isNoContent)
        report(alice, id).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("REVIEW.OWN_REVIEW"))
        report(bob, id, "NOPE").andExpect(status().isBadRequest)
        mvc.perform(post("$BASE_PATH/$id/reports").contentType(MediaType.APPLICATION_JSON).content("""{"reason":"SPAM"}""")).andExpect(status().isUnauthorized)
        assertEquals(1, store.find(id)!!.reportCount)
    }

    @Test
    fun `the admin paths need a login and the permission`() {
        mvc.perform(get(admin)).andExpect(status().isUnauthorized)
        mvc.perform(get(admin).principal(alice)).andExpect(status().isForbidden)
        mvc.perform(put("$admin/rvw_x/moderation").principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"status":"HIDDEN","reason":"x"}""")).andExpect(status().isForbidden)
        mvc.perform(put("$admin/rvw_x/reply").principal(mod).contentType(MediaType.APPLICATION_JSON).content("""{"body":"hi"}""")).andExpect(status().isForbidden)   // 답글은 전권만
        mvc.perform(get(admin).principal(mod)).andExpect(status().isOk)
    }

    @Test
    fun `the reported queue lists unreviewed reports with reason counts and the label only for an account reader`() {
        val id = write(alice)
        report(bob, id).andExpect(status().isNoContent)
        report(TestingAuthenticationToken("acc_carol_secret", "n/a", "ROLE_USER"), id, "PRIVACY").andExpect(status().isNoContent)
        val body = mvc.perform(get(admin).principal(mod))
            .andExpect(jsonPath("$.values[0].id").value(id))
            .andExpect(jsonPath("$.values[0].reportCount").value(2))
            .andExpect(jsonPath("$.values[0].reportReasons.SPAM").value(1))
            .andExpect(jsonPath("$.values[0].reportReasons.PRIVACY").value(1))
            .andExpect(jsonPath("$.values[0].accountLabel").doesNotExist())   // account.read 가 없으면 이름도 없다
            .andExpect(jsonPath("$.values[0].lastReportedAt").isString)
            .andExpect(jsonPath("$.cursor.hasNext").value(false))
            .andReturn().response.contentAsString
        assertFalse(Regex("acc_[a-z]+_secret").containsMatchIn(body), "an account id leaked to the operator list: $body")
        ReviewAdminTestBeans.GRANTS["acc_mod_secret"] = setOf("review.moderate", "account.read")
        mvc.perform(get(admin).principal(mod)).andExpect(jsonPath("$.values[0].accountLabel").value("acc_alice_secret@example.com"))
        mvc.perform(get("$admin?queue=hidden").principal(mod)).andExpect(jsonPath("$.values.length()").value(0))
        mvc.perform(get("$admin?queue=bogus").principal(mod)).andExpect(status().isBadRequest)
    }

    @Test
    fun `hide then restore - 204s, the aggregate follows, others never see the body or reason, the author sees both, audit records`() {
        val id = write(alice, 5, "alice words that get hidden")
        write(bob, 3, "bob stays visible here")
        mvc.perform(put("$admin/$id/moderation").principal(mod).contentType(MediaType.APPLICATION_JSON).content("""{"status":"HIDDEN","reason":"reason text rude"}""")).andExpect(status().isNoContent)
        val dave = TestingAuthenticationToken("acc_dave_secret", "n/a", "ROLE_USER")
        val seenByOthers = listOf(
            mvc.perform(get(reviewsPath()).principal(dave)).andReturn().response.contentAsString,
            mvc.perform(get("$BASE_PATH/targets/product/basic/summary").principal(dave)).andReturn().response.contentAsString,
        )
        seenByOthers.forEach { listOf("alice words that get hidden", "reason text rude").forEach { s -> assertFalse(s in it, "'$s' leaked") } }
        mvc.perform(get("$BASE_PATH/targets/product/basic/summary").principal(dave)).andExpect(jsonPath("$.value.count").value(1))
        mvc.perform(get(reviewsPath()).principal(alice))
            .andExpect(jsonPath("$.values[?(@.mine == true)].status").value("HIDDEN"))
            .andExpect(jsonPath("$.values[?(@.mine == true)].moderationReason").value("reason text rude"))
        mvc.perform(get("$admin?queue=hidden").principal(mod)).andExpect(jsonPath("$.values[0].id").value(id)).andExpect(jsonPath("$.values[0].moderationReason").value("reason text rude"))
        mvc.perform(put("$admin/$id/moderation").principal(mod).contentType(MediaType.APPLICATION_JSON).content("""{"status":"PUBLISHED"}""")).andExpect(status().isNoContent)
        mvc.perform(get("$BASE_PATH/targets/product/basic/summary").principal(dave)).andExpect(jsonPath("$.value.count").value(2))
        mvc.perform(put("$admin/$id/moderation").principal(mod).contentType(MediaType.APPLICATION_JSON).content("""{"status":"HIDDEN"}""")).andExpect(status().isBadRequest)   // 사유 필수
        mvc.perform(put("$admin/$id/moderation").principal(mod).contentType(MediaType.APPLICATION_JSON).content("""{"status":"WHATEVER","reason":"x"}""")).andExpect(status().isBadRequest)
        assertEquals(listOf("review.hidden", "review.restored"), ReviewAdminTestBeans.AUDIT.map { it.action })
        assertTrue(ReviewAdminTestBeans.AUDIT.all { it.targetType == "review" && it.targetId == id })
    }

    @Test
    fun `dismiss answers 204 and leaves the queue, a missing review is 404`() {
        val id = write(alice)
        report(bob, id).andExpect(status().isNoContent)
        mvc.perform(post("$admin/$id/reports/dismiss").principal(mod)).andExpect(status().isNoContent)
        mvc.perform(get(admin).principal(mod)).andExpect(jsonPath("$.values.length()").value(0))
        mvc.perform(post("$admin/rvw_none/reports/dismiss").principal(mod)).andExpect(status().isNotFound)
        assertEquals(listOf("review.reports_dismissed"), ReviewAdminTestBeans.AUDIT.map { it.action })
    }

    @Test
    fun `a reply is shown on the public line in the front end's shape and can be removed`() {
        val id = write(alice)
        mvc.perform(put("$admin/$id/reply").principal(boss).contentType(MediaType.APPLICATION_JSON).content("""{"body":"thank you for the review"}""")).andExpect(status().isNoContent)
        mvc.perform(get(reviewsPath()).principal(bob))
            .andExpect(jsonPath("$.values[0].reply.body").value("thank you for the review"))
            .andExpect(jsonPath("$.values[0].reply.createdAt").isString)
            .andExpect(jsonPath("$.values[0].reply.updatedAt").isString)
        mvc.perform(get("$BASE_PATH/config").principal(boss)).andExpect(jsonPath("$.value.canReply").value(true)).andExpect(jsonPath("$.value.canModerate").value(true))
        mvc.perform(get("$BASE_PATH/config").principal(mod)).andExpect(jsonPath("$.value.canReply").value(false)).andExpect(jsonPath("$.value.canModerate").value(true))
        mvc.perform(delete("$admin/$id/reply").principal(boss)).andExpect(status().isNoContent)
        mvc.perform(get(reviewsPath()).principal(bob)).andExpect(jsonPath("$.values[0].reply").doesNotExist())
        assertEquals(listOf("review.replied", "review.replied"), ReviewAdminTestBeans.AUDIT.map { it.action })
    }

    @Test
    fun `photos are stored as keys and shown through a private 302 to a short signed address with no account id`() {
        val created = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(reviewsPath()).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"body":"with a photo","photos":["reviews/one.jpg"]}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.value.photos[0].index").value(0)).andReturn().response.contentAsString
        val id = mapper.readTree(created).path("value").path("id").asString()
        mvc.perform(get(reviewsPath()).principal(alice)).andExpect(jsonPath("$.values[0].photos[0].key").value("reviews/one.jpg"))   // 본인에게만 키가 간다
        val others = mvc.perform(get(reviewsPath()).principal(bob)).andExpect(jsonPath("$.values[0].photos[0].index").value(0)).andReturn().response.contentAsString
        assertFalse("reviews/one.jpg" in others, "the storage key must not reach other readers")
        val response = mvc.perform(get("$BASE_PATH/$id/photos/0").principal(bob))
            .andExpect(status().isFound)
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
            .andReturn().response
        assertEquals("https://files.example.com/signed/reviews/one.jpg?sig=abc", response.getHeader("Location"))
        assertFalse(Regex("acc_[a-z]+_secret").containsMatchIn(response.getHeader("Location")!!))
        mvc.perform(get("$BASE_PATH/$id/photos/0")).andExpect(status().isUnauthorized)
        mvc.perform(get("$BASE_PATH/$id/photos/1").principal(bob)).andExpect(status().isNotFound)
        mvc.perform(put("$admin/$id/moderation").principal(mod).contentType(MediaType.APPLICATION_JSON).content("""{"status":"HIDDEN","reason":"x"}""")).andExpect(status().isNoContent)
        mvc.perform(get("$BASE_PATH/$id/photos/0").principal(bob)).andExpect(status().isNotFound)   // 숨기는 순간 바로 안 보인다
        mvc.perform(get("$BASE_PATH/$id/photos/0").principal(alice)).andExpect(status().isFound)
        mvc.perform(get("$BASE_PATH/$id/photos/0").principal(mod)).andExpect(status().isFound)
        assertEquals(ReviewStatus.HIDDEN, store.find(id)!!.status)
    }

    @Test
    fun `a photo key holding the author's account id is refused at write time`() {
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(reviewsPath()).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"body":"x","photos":["uploads/acc_alice_secret/one.jpg"]}"""),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REVIEW.CONTENT_INVALID"))
    }

    private companion object {
        const val BASE_PATH = "/api/v1/reviews"
    }
}

/** 운영 경로는 기본 꺼짐 - 경로가 없어 404. 신고는 사용자 경로라 열려 있다 */
@SpringBootTest(classes = [ReviewWebTestApplication::class], properties = ["skeleton.review.target-types=product"])
@AutoConfigureMockMvc
@Import(ReviewAdminTestBeans::class)
class ReviewAdminOffWebTest : ReviewWebBase() {
    @Test
    fun `the admin paths do not exist by default`() {
        val boss = TestingAuthenticationToken("acc_boss_secret", "n/a", "ROLE_ADMIN")
        ReviewAdminTestBeans.GRANTS["acc_boss_secret"] = setOf("*")
        mvc.perform(get("/api/v1/admin/reviews").principal(boss)).andExpect(status().isNotFound)
        mvc.perform(put("/api/v1/admin/reviews/rvw_x/moderation").principal(boss).contentType(MediaType.APPLICATION_JSON).content("""{"status":"HIDDEN","reason":"x"}""")).andExpect(status().isNotFound)
        mvc.perform(put("/api/v1/admin/reviews/rvw_x/reply").principal(boss).contentType(MediaType.APPLICATION_JSON).content("""{"body":"x"}""")).andExpect(status().isNotFound)
    }
}
