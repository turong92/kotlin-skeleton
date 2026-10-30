package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import dev.sumin.skeleton.review.FakeReviewStore
import dev.sumin.skeleton.review.ReviewPurchases
import dev.sumin.skeleton.review.ReviewStatus
import dev.sumin.skeleton.review.ReviewTarget
import dev.sumin.skeleton.reviewtest.ReviewWebTestApplication
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

@TestConfiguration(proxyBeanMethods = false)
class ReviewTestBeans {
    @Bean fun fakeStore() = FakeReviewStore()
}

internal const val BASE = "/api/v1/reviews"

/** 쓰기에는 `Idempotency-Key` 가 필요하다 - 시험마다 새 키 */
internal fun post(path: String): MockHttpServletRequestBuilder = MockMvcRequestBuilders.post(path).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
private val PRODUCT = "product" to "basic"

abstract class ReviewWebBase {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeReviewStore
    @Autowired lateinit var purchases: ReviewPurchases
    @Autowired lateinit var mapper: ObjectMapper

    val alice = TestingAuthenticationToken("acc_alice_secret", "n/a", "ROLE_USER")
    val bob = TestingAuthenticationToken("acc_bob_secret", "n/a", "ROLE_USER")

    @BeforeEach fun clean() = store.clear()

    fun reviewsPath(type: String = PRODUCT.first, id: String = PRODUCT.second) = "$BASE/targets/$type/$id/reviews"

    fun write(auth: TestingAuthenticationToken, rating: Int = 5, body: String = "great product, would buy again"): String {
        val json = mvc.perform(
            post(reviewsPath()).principal(auth).contentType(MediaType.APPLICATION_JSON).content("""{"rating":$rating,"body":"$body"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return mapper.readTree(json).path("value").path("id").asString()
    }
}

/** 기본 설정 - 읽기도 로그인이 필요하고, 응답은 계정 id 를 어디에도 담지 않는다 */
@SpringBootTest(classes = [ReviewWebTestApplication::class], properties = ["skeleton.review.target-types=product"])
@AutoConfigureMockMvc
@Import(ReviewTestBeans::class)
class ReviewWebTest : ReviewWebBase() {
    @Autowired lateinit var contributors: org.springframework.beans.factory.ObjectProvider<PublicEndpointContributor>

    @Test
    fun `by default every path needs a login, reads included`() {
        mvc.perform(get("$BASE/targets/product/basic/summary")).andExpect(status().isUnauthorized)
        mvc.perform(get(reviewsPath())).andExpect(status().isUnauthorized)
        mvc.perform(get("$BASE/summaries?targets=product:basic")).andExpect(status().isUnauthorized)
        mvc.perform(get("$BASE/config")).andExpect(status().isUnauthorized)
        mvc.perform(get("$BASE/me")).andExpect(status().isUnauthorized)
        mvc.perform(post(reviewsPath()).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5}""")).andExpect(status().isUnauthorized)
        assertTrue(PublicEndpointRegistry.from(WebProperties(), contributors.orderedStream().toList()).endpoints.none { it.pattern.startsWith(BASE) })
    }

    @Test
    fun `config tells the front end what is open`() {
        mvc.perform(get("$BASE/config").principal(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.targetTypes[0]").value("product"))
            .andExpect(jsonPath("$.value.bodyMaxLength").value(2000))
            .andExpect(jsonPath("$.value.reportReasons.length()").value(5))
            .andExpect(jsonPath("$.value.canModerate").value(false))
    }

    @Test
    fun `writing answers 201 with a Location, the review in its public shape, and a second time is 409`() {
        val result = mvc.perform(post(reviewsPath()).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":4,"body":"works fine"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.rating").value(4))
            .andExpect(jsonPath("$.value.mine").value(true))
            .andExpect(jsonPath("$.value.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.value.verified").value(false))
            .andExpect(jsonPath("$.value.edited").value(false))
            .andExpect(jsonPath("$.value.photos.length()").value(0))
            .andExpect(jsonPath("$.value.reply").doesNotExist())
            .andReturn().response
        val id = mapper.readTree(result.contentAsString).path("value").path("id").asString()
        assertTrue(result.getHeader("Location")!!.endsWith("$BASE/$id"), result.getHeader("Location"))
        mvc.perform(post(reviewsPath()).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":1,"body":"again"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVIEW.ALREADY_WRITTEN"))
    }

    @Test
    fun `an unconfigured type is 404 and a bad rating is 400`() {
        mvc.perform(post(reviewsPath("post", "1")).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"body":"x"}"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("REVIEW.TARGET_NOT_FOUND"))
        mvc.perform(post(reviewsPath()).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":9,"body":"x"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REVIEW.CONTENT_INVALID"))
        mvc.perform(post(reviewsPath()).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"body":"no stars"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REVIEW.CONTENT_INVALID"))
    }

    @Test
    fun `no response anywhere carries an account id, and others never see a hidden or deleted body`() {
        val own = write(alice, 5, "alice public words")
        val hidden = write(bob, 1, "bob hidden words")
        store.put(store.find(hidden)!!.copy(status = ReviewStatus.HIDDEN, moderatedBy = "acc_op_secret", moderationReason = "hidden because of rude words"))
        val carol = TestingAuthenticationToken("acc_carol_secret", "n/a", "ROLE_USER")
        val deleted = write(carol, 2, "carol deleted words")
        mvc.perform(delete("$BASE/$deleted").principal(carol)).andExpect(status().isNoContent)
        mvc.perform(put("$BASE/$own/helpful").principal(bob)).andExpect(status().isOk)

        val dave = TestingAuthenticationToken("acc_dave_secret", "n/a", "ROLE_USER")
        val bodies = listOf(
            mvc.perform(get(reviewsPath()).principal(dave)).andReturn().response.contentAsString,
            mvc.perform(get("$BASE/targets/product/basic/summary").principal(dave)).andReturn().response.contentAsString,
            mvc.perform(get("$BASE/summaries?targets=product:basic").principal(dave)).andReturn().response.contentAsString,
            mvc.perform(get("$BASE/me").principal(alice)).andReturn().response.contentAsString,
            mvc.perform(get(reviewsPath()).principal(alice)).andReturn().response.contentAsString,
            mvc.perform(put("$BASE/$own/helpful").principal(dave)).andReturn().response.contentAsString,
            mvc.perform(patch("$BASE/$own").principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":4}""")).andReturn().response.contentAsString,
            mvc.perform(get(reviewsPath()).principal(bob)).andReturn().response.contentAsString,
        )
        bodies.forEach { body -> assertFalse(Regex("acc_[a-z]+_secret|acc_op").containsMatchIn(body), "an account id leaked: $body") }
        val forOthers = bodies[0]
        assertTrue("alice public words" in forOthers)
        listOf("bob hidden words", "hidden because of rude words", "carol deleted words").forEach { assertFalse(it in forOthers, "'$it' leaked to another reader") }
        assertFalse("bob hidden words" in bodies[3] || "bob hidden words" in bodies[4])
        // 본인에게는 숨겨짐 + 사유가 보인다
        assertTrue("hidden because of rude words" in bodies[7])
    }

    @Test
    fun `only the own line carries status, and edits and deletes are the author's`() {
        val id = write(alice, 3, "okay product overall")
        mvc.perform(get(reviewsPath()).principal(bob))
            .andExpect(jsonPath("$.values[0].mine").value(false))
            .andExpect(jsonPath("$.values[0].status").doesNotExist())
            .andExpect(jsonPath("$.values[0].moderationReason").doesNotExist())
            .andExpect(jsonPath("$.pagination.totalElements").value(1))
        mvc.perform(patch("$BASE/$id").principal(bob).contentType(MediaType.APPLICATION_JSON).content("""{"rating":1}""")).andExpect(status().isForbidden)
        mvc.perform(patch("$BASE/$id").principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":2,"body":"worse after a week"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.rating").value(2))
            .andExpect(jsonPath("$.value.body").value("worse after a week"))
        mvc.perform(delete("$BASE/$id").principal(bob)).andExpect(status().isForbidden)
        mvc.perform(delete("$BASE/$id").principal(alice)).andExpect(status().isNoContent)
        mvc.perform(delete("$BASE/$id").principal(alice)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("REVIEW.NOT_FOUND"))
    }

    @Test
    fun `helpful puts and removes with the server count, never on your own review`() {
        val id = write(alice)
        mvc.perform(put("$BASE/$id/helpful").principal(alice)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("REVIEW.OWN_REVIEW"))
        mvc.perform(put("$BASE/$id/helpful").principal(bob)).andExpect(jsonPath("$.value.helpfulCount").value(1)).andExpect(jsonPath("$.value.helpful").value(true))
        mvc.perform(delete("$BASE/$id/helpful").principal(bob)).andExpect(jsonPath("$.value.helpfulCount").value(0)).andExpect(jsonPath("$.value.helpful").value(false))
    }

    @Test
    fun `summary, batch summaries, filters and sorts speak the front end's shapes`() {
        write(alice, 5)
        write(bob, 4, "good enough for me")
        mvc.perform(get("$BASE/targets/product/basic/summary").principal(alice))
            .andExpect(jsonPath("$.value.count").value(2))
            .andExpect(jsonPath("$.value.average").value(4.5))
            .andExpect(jsonPath("$.value.distribution.5").value(1))
            .andExpect(jsonPath("$.value.distribution.1").value(0))
            .andExpect(jsonPath("$.value.canWrite").value(false))
            .andExpect(jsonPath("$.value.writeBlockedReason").value("ALREADY_WRITTEN"))
            .andExpect(jsonPath("$.value.myReviewId").isString)
        mvc.perform(get("$BASE/summaries?targets=product:basic,product:pro").principal(alice))
            .andExpect(jsonPath("$.values[0].targetId").value("basic"))
            .andExpect(jsonPath("$.values[0].count").value(2))
            .andExpect(jsonPath("$.values[1].count").value(0))
            .andExpect(jsonPath("$.values[1].average").doesNotExist())
        mvc.perform(get(reviewsPath() + "?sort=rating_low&rating=4").principal(alice)).andExpect(jsonPath("$.values.length()").value(1))
        mvc.perform(get(reviewsPath() + "?sort=nonsense").principal(alice)).andExpect(status().isBadRequest)
        mvc.perform(get(reviewsPath() + "?size=1000").principal(alice)).andExpect(status().isBadRequest)
        val tooMany = (1..51).joinToString(",") { "product:p$it" }
        mvc.perform(get("$BASE/summaries?targets=$tooMany").principal(alice)).andExpect(status().isBadRequest)
        mvc.perform(get("$BASE/summaries?targets=broken").principal(alice)).andExpect(status().isBadRequest)
    }

    @Test
    fun `my reviews page by cursor with the target, status and reason`() {
        write(alice, 5)
        mvc.perform(post(reviewsPath("product", "pro")).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":3,"body":"second product"}"""))
            .andExpect(status().isCreated)
        val first = mvc.perform(get("$BASE/me?size=1").principal(alice))
            .andExpect(jsonPath("$.values[0].targetType").value("product"))
            .andExpect(jsonPath("$.values[0].targetId").value("pro"))
            .andExpect(jsonPath("$.values[0].status").value("PUBLISHED"))
            .andExpect(jsonPath("$.cursor.hasNext").value(true))
            .andReturn().response.contentAsString
        val cursor = mapper.readTree(first).path("cursor").path("nextCursor").asString()
        mvc.perform(get("$BASE/me?size=1&cursor=$cursor").principal(alice))
            .andExpect(jsonPath("$.values[0].targetId").value("basic"))
            .andExpect(jsonPath("$.cursor.hasNext").value(false))
        mvc.perform(get("$BASE/me").principal(bob)).andExpect(jsonPath("$.values.length()").value(0))
    }

    @Test
    fun `purchases verify a review in the response`() {
        val id = write(alice)
        purchases.record("acc_alice_secret", ReviewTarget("product", "basic"), "order:1")
        mvc.perform(get(reviewsPath() + "?verified=true").principal(bob))
            .andExpect(jsonPath("$.values.length()").value(1))
            .andExpect(jsonPath("$.values[0].id").value(id))
            .andExpect(jsonPath("$.values[0].verified").value(true))
    }
}

/** 앱이 비로그인 읽기를 열었을 때 - 읽기 셋만 열리고 나머지는 닫혀 있다 */
@SpringBootTest(classes = [ReviewWebTestApplication::class], properties = ["skeleton.review.target-types=product", "skeleton.review.http.allow-anonymous-read=true"])
@AutoConfigureMockMvc
@Import(ReviewTestBeans::class)
class ReviewAnonymousReadWebTest : ReviewWebBase() {
    @Autowired lateinit var contributors: List<PublicEndpointContributor>

    @Test
    fun `the three reads answer 200 without a login and carry no write state`() {
        write(alice)
        mvc.perform(get(reviewsPath())).andExpect(status().isOk).andExpect(jsonPath("$.values.length()").value(1)).andExpect(jsonPath("$.values[0].mine").value(false)).andExpect(jsonPath("$.values[0].helpful").value(false))
        mvc.perform(get("$BASE/targets/product/basic/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.canWrite").value(false))
            .andExpect(jsonPath("$.value.writeBlockedReason").value("SIGN_IN"))
        mvc.perform(get("$BASE/summaries?targets=product:basic")).andExpect(status().isOk)
        mvc.perform(get("$BASE/config")).andExpect(status().isUnauthorized)
        mvc.perform(get("$BASE/me")).andExpect(status().isUnauthorized)
        mvc.perform(post(reviewsPath()).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5}""")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `exactly the three read patterns are declared public`() {
        val declared = PublicEndpointRegistry.from(WebProperties(), contributors).endpoints.filter { it.pattern.startsWith(BASE) }.map { it.method to it.pattern }.toSet()
        assertEquals(
            setOf(HttpMethod.GET to "$BASE/summaries", HttpMethod.GET to "$BASE/targets/*/*/reviews", HttpMethod.GET to "$BASE/targets/*/*/summary"),
            declared,
        )
    }
}

/** 구매자만 쓰는 설정 */
@SpringBootTest(classes = [ReviewWebTestApplication::class], properties = ["skeleton.review.target-types=product", "skeleton.review.require-purchase=true"])
@AutoConfigureMockMvc
@Import(ReviewTestBeans::class)
class ReviewRequirePurchaseWebTest : ReviewWebBase() {
    @Test
    fun `a non buyer gets 403 and a buyer writes a verified review`() {
        mvc.perform(post(reviewsPath()).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"body":"x"}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("REVIEW.PURCHASE_REQUIRED"))
        mvc.perform(get("$BASE/targets/product/basic/summary").principal(alice)).andExpect(jsonPath("$.value.writeBlockedReason").value("PURCHASE_REQUIRED"))
        purchases.record("acc_alice_secret", ReviewTarget("product", "basic"), "order:1")
        mvc.perform(post(reviewsPath()).principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"body":"bought it"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.verified").value(true))
    }
}
