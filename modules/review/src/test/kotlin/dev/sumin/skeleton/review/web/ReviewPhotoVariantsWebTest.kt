package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.review.FakeReviewStore
import dev.sumin.skeleton.reviewtest.ReviewWebTestApplication
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class FakeUploadImages : UploadImages {
    val statuses = ConcurrentHashMap<String, UploadImageStatus>()
    val ensured = CopyOnWriteArrayList<String>()
    override val variantPrefix = "variants"
    override fun status(privateKey: String) = statuses[privateKey]
    override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String? =
        statuses[privateKey]?.takeIf { it.state == UploadImageState.READY && variant in it.variants }?.let { "https://files.example.com/variant/$privateKey/$variant" }
    override fun ensure(privateKey: String) { ensured += privateKey }
    override fun forget(privateKeys: Collection<String>) = Unit
}

@TestConfiguration(proxyBeanMethods = false)
class ReviewPhotoVariantsBeans {
    @Bean fun fakeStore() = FakeReviewStore()
    @Bean fun policy(): dev.sumin.skeleton.review.ReviewPolicy = object : dev.sumin.skeleton.review.ReviewPolicy { override fun canAttach(accountId: String, keys: List<String>) = true }
    @Bean fun uploads(): PrivateUploadAccess = PrivateUploadAccess { key, _ -> "https://files.example.com/signed/$key?sig=abc" }
    @Bean fun images() = FakeUploadImages()
}

/** 이미지 모듈이 있을 때 리뷰 사진 - 남에게는 변환본만, 올린 사람은 변환 중에도 원본, 이미지가 아닌 첨부는 지금처럼 원본 */
@SpringBootTest(classes = [ReviewWebTestApplication::class], properties = ["skeleton.review.target-types=product", "skeleton.review.max-photos=3"])
@AutoConfigureMockMvc
@Import(ReviewPhotoVariantsBeans::class)
class ReviewPhotoVariantsWebTest : ReviewWebBase() {
    @Autowired lateinit var images: FakeUploadImages

    private fun review(vararg keys: String): String {
        val created = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(reviewsPath()).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .principal(alice).contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"body":"with photos","photos":[${keys.joinToString { "\"$it\"" }}]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return mapper.readTree(created).path("value").path("id").asString()
    }

    private fun location(id: String, who: org.springframework.security.authentication.TestingAuthenticationToken, query: String = "") =
        mvc.perform(get("$BASE/$id/photos/0$query").principal(who)).andReturn().response.let { it.status to it.getHeader("Location") }

    @Test
    fun `others get the ready variant, never the original - while it is not ready they get 404 and the key is registered, the uploader still sees the original`() {
        images.statuses.clear(); images.ensured.clear()
        val id = review("reviews/one.jpg")
        assertEquals(404 to null, location(id, bob), "no row yet: the original with its GPS must not reach another person")
        assertEquals(listOf("reviews/one.jpg"), images.ensured.distinct(), "the lazy registration is asked for")
        assertEquals(302 to "https://files.example.com/signed/reviews/one.jpg?sig=abc", location(id, alice), "the uploader may see the original while it is being made")

        images.statuses["reviews/one.jpg"] = UploadImageStatus(
            UploadImageState.READY, 4000, 3000,
            mapOf(
                "thumb" to dev.sumin.skeleton.common.upload.UploadImageVariant("v/thumb.jpg", 400, 300, "image/jpeg"),
                "display" to dev.sumin.skeleton.common.upload.UploadImageVariant("v/display.jpg", 1600, 1200, "image/jpeg"),
            ),
        )
        assertEquals(302 to "https://files.example.com/variant/reviews/one.jpg/display", location(id, bob), "display is the default")
        assertEquals(302 to "https://files.example.com/variant/reviews/one.jpg/thumb", location(id, bob, "?variant=thumb"))

        images.statuses["reviews/one.jpg"] = UploadImageStatus(UploadImageState.REJECTED)
        assertEquals(404, location(id, bob).first, "a refused image is not shown to others either")
    }

    @Test
    fun `a file that is not an image is served as before even with the image module`() {
        images.statuses.clear()
        val id = review("reviews/receipt.pdf")
        assertEquals(302 to "https://files.example.com/signed/reviews/receipt.pdf?sig=abc", location(id, bob))
    }
}
