package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImageVariant
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.StorageKeyScheme
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.storage.image.web.StorageImagesController
import dev.sumin.skeleton.storage.image.web.StorageImagesRequest
import dev.sumin.skeleton.storage.image.web.StorageImagesResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class StorageImagesControllerTest {
    private val signed = mutableListOf<String>()
    private val ensured = mutableListOf<String>()
    private val rows = mutableMapOf(
        "uploads/7/a/x.jpg" to UploadImageStatus(UploadImageState.READY, 200, 100, mapOf(UploadImages.THUMB to UploadImageVariant("variants/h/thumb.jpg", 100, 50, "image/jpeg"))),
        "uploads/7/m/x.gif" to UploadImageStatus(
            UploadImageState.READY, 200, 100,
            mapOf(
                UploadImages.THUMB to UploadImageVariant("variants/m/thumb.jpg", 100, 50, "image/jpeg"),
                UploadImages.DISPLAY to UploadImageVariant("variants/m/display.mp4", 200, 100, "video/mp4"),
            ),
        ),
        "uploads/8/b/x.jpg" to UploadImageStatus(UploadImageState.READY, 20, 10, mapOf(UploadImages.THUMB to UploadImageVariant("variants/h2/thumb.jpg", 20, 10, "image/jpeg"))),
    )
    private val images = object : UploadImages {
        override val variantPrefix = "variants"
        override fun status(privateKey: String) = rows[privateKey]
        override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String? {
            signed += privateKey
            return rows[privateKey]?.variants?.get(variant)?.let { "https://s3.test/${it.key}?sig" }
        }
        override fun ensure(privateKey: String) {
            ensured += privateKey
            rows.putIfAbsent(privateKey, UploadImageStatus(UploadImageState.PENDING))
        }
        override fun forget(privateKeys: Collection<String>) = Unit
    }
    private val controller = StorageImagesController(images, StorageKeyScheme(StorageWebProperties()), Duration.ofMinutes(10))
    private val me = UsernamePasswordAuthenticationToken("7", null, emptyList())

    @Test
    fun `only the callers own keys are answered and signed - another account's key is left out`() {
        val answer = controller.images(me, StorageImagesRequest(listOf("uploads/7/a/x.jpg", "uploads/8/b/x.jpg", "../etc/passwd", "uploads/7/fresh/y.png")), null, org.springframework.mock.web.MockHttpServletResponse())

        @Suppress("UNCHECKED_CAST")
        val items = ((answer as DataResponse<StorageImagesResponse>).value).items
        assertEquals(listOf("uploads/7/a/x.jpg", "uploads/7/fresh/y.png"), items.map { it.key })
        assertEquals("https://s3.test/variants/h/thumb.jpg?sig", items[0].variants.getValue("thumb").url)
        assertEquals("PENDING", items[1].status)
        assertEquals(listOf("uploads/7/a/x.jpg"), signed, "no one else's key is ever signed")
        assertEquals(listOf("uploads/7/fresh/y.png"), ensured)
        assertTrue(items.none { it.key.startsWith("uploads/8") })
    }

    @Test
    fun `each variant says its content type - a converted animated display is video mp4 so the screen knows to play it`() {
        val answer = controller.images(me, StorageImagesRequest(listOf("uploads/7/m/x.gif")), null, org.springframework.mock.web.MockHttpServletResponse())

        @Suppress("UNCHECKED_CAST")
        val item = ((answer as DataResponse<StorageImagesResponse>).value).items.single()
        assertEquals("video/mp4", item.variants.getValue("display").contentType)
        assertEquals("image/jpeg", item.variants.getValue("thumb").contentType)
    }
}
