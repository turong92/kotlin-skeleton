package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImageVariant
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.common.upload.UploadVariantsPendingException
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class StorageImageVariantsTest {
    private val storage = FakePresignedStorage()
    private val web = StorageWebProperties(
        keyOwner = StorageKeyOwner.OPAQUE, keySecret = "x".repeat(32),
        `public` = StoragePublicProperties(enabled = true, secret = "public-secret-for-tests-0123456789abcdef", baseUrl = "https://cdn.example.com"),
        guest = StorageGuestProperties(enabled = true, secret = "guest-secret-for-tests-0123456789abcdef", cleanup = StorageGuestProperties.GuestCleanup(enabled = true)),
    )
    private val scheme = StorageKeyScheme(web)

    /** 상태를 손으로 정하는 이미지 모듈 - forget 은 실제 구현처럼 그 키의 변환본 폴더를 지운다 */
    private class FakeImages(private val storage: FakePresignedStorage) : UploadImages {
        override val variantPrefix = "variants"
        val statuses = mutableMapOf<String, UploadImageStatus>()
        val ensured = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        override fun status(privateKey: String) = statuses[privateKey]
        override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String? = null
        override fun ensure(privateKey: String) { ensured += privateKey }
        override fun forget(privateKeys: Collection<String>) {
            forgotten += privateKeys
            privateKeys.forEach { key -> storage.objects.keys.filter { it.startsWith("variants/${key.hashCode()}/") }.forEach(storage.objects::remove) }
        }
    }

    private val images = FakeImages(storage)
    private val uploads = StoragePublicUploads(storage, scheme, { images })

    private fun ready(key: String) = UploadImageStatus(
        UploadImageState.READY, 4000, 3000,
        mapOf(
            "thumb" to UploadImageVariant("variants/${key.hashCode()}/thumb.jpg", 400, 300, "image/jpeg"),
            "display" to UploadImageVariant("variants/${key.hashCode()}/display.jpg", 1600, 1200, "image/jpeg"),
        ),
    ).also {
        storage.objects["variants/${key.hashCode()}/thumb.jpg"] = "thumb-bytes".toByteArray() to "image/jpeg"
        storage.objects["variants/${key.hashCode()}/display.jpg"] = "display-bytes".toByteArray() to "image/jpeg"
    }

    @Test
    fun `with the image module the public area gets the variants and never the original - until they exist, nothing is copied`() {
        val key = scheme.issue("acc_a", "photo.jpg")
        storage.objects[key] = "original-with-gps".toByteArray() to "image/jpeg"

        assertFailsWith<UploadVariantsPendingException> { uploads.publish(key) }
        assertEquals(listOf(key), images.ensured, "an unregistered key is registered so the worker makes the variants")
        images.statuses[key] = UploadImageStatus(UploadImageState.PENDING)
        assertFailsWith<UploadVariantsPendingException> { uploads.publish(key) }
        images.statuses[key] = UploadImageStatus(UploadImageState.REJECTED)
        assertFalse(uploads.publish(key), "an image the engine refuses is not published either")
        assertTrue(storage.objects.keys.none { it.startsWith("public/") }, "the original never reaches the public area")

        images.statuses[key] = ready(key)
        assertTrue(uploads.publish(key))
        assertEquals("display-bytes", String(storage.objects.getValue(scheme.publicKeyOf(key)).first))
        val (thumbKey) = uploads.publicKeysOf(key).drop(1)
        assertEquals("thumb-bytes", String(storage.objects.getValue(thumbKey).first))
    }

    @Test
    fun `publicKeysOf names the thumbnail copy too, so a full reconciliation never sees it as an orphan`() {
        val key = scheme.issue("acc_a", "photo.jpg")
        images.statuses[key] = ready(key)
        storage.objects[key] = "original".toByteArray() to "image/jpeg"
        uploads.publish(key)

        val pointedTo = uploads.publicKeysOf(key).toSet()
        assertEquals(2, pointedTo.size)
        assertEquals(storage.objects.keys.filter { it.startsWith("public/") }.toSet(), pointedTo, "every public object is accounted for by the row that points at it")
        assertEquals(uploads.listPublicKeys().toSet(), pointedTo)

        uploads.unpublish(key)
        assertTrue(storage.objects.keys.none { it.startsWith("public/") }, "unpublish removes the thumbnail as well")
        assertEquals(listOf(scheme.publicKeyOf(key)), StoragePublicUploads(storage, scheme).publicKeysOf(key), "without the image module it is the one key it always was")
    }

    @Test
    fun `imagesOf describes only ready images, with the public addresses of the display copy and the thumbnail`() {
        val readyKey = scheme.issue("acc_a", "photo.jpg")
        val pendingKey = scheme.issue("acc_a", "wait.jpg")
        val pdfKey = scheme.issue("acc_a", "doc.pdf")
        images.statuses[readyKey] = ready(readyKey)
        images.statuses[pendingKey] = UploadImageStatus(UploadImageState.PENDING)

        val found = uploads.imagesOf(listOf(readyKey, pendingKey, pdfKey, scheme.issue("acc_a", "unknown.jpg")))
        assertEquals(setOf(readyKey), found.keys, "pending, non-image and unregistered keys are left out")
        val image = found.getValue(readyKey)
        assertEquals(1600 to 1200, image.width to image.height, "the size of the copy people get, not of the original")
        assertEquals(uploads.urlOf(readyKey), image.display)
        assertEquals(uploads.urlOf(readyKey).substringBeforeLast('.') + ".thumb." + uploads.urlOf(readyKey).substringAfterLast('.'), image.thumb)
        assertEquals(emptyMap(), StoragePublicUploads(storage, scheme).imagesOf(listOf(readyKey)), "without the image module there is nothing to describe")
    }

    @Test
    fun `guest cleanup also forgets the variants of the key it deletes and leaves other keys' variants alone`() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val old = now.minus(Duration.ofHours(30))
        val orphan = scheme.issueGuest("image/png")
        val attached = scheme.issueGuest("image/png")
        listOf(orphan, attached).forEach { storage.objects[it] = "x".toByteArray() to "image/png"; images.statuses[it] = ready(it) }
        val listing = AgedListing(storage, old)
        val cleanup = StorageGuestCleanup(listing, scheme, { UploadedKeyUsage { keys -> keys.filter { it == attached }.toSet() } }, TimeProvider.fixed(now), images = { images })

        assertEquals(1, cleanup.run())
        assertEquals(listOf(orphan), images.forgotten)
        assertTrue(storage.objects.keys.none { it.startsWith("variants/${orphan.hashCode()}/") })
        assertTrue(storage.objects.keys.any { it.startsWith("variants/${attached.hashCode()}/") }, "a key still in use keeps its variants")
    }

    @Test
    fun `the variant prefix may not overlap the upload prefix or the public prefix - the app refuses to start`() {
        fun runner(variants: String) = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration::class.java))
            .withBean(UploadImages::class.java, java.util.function.Supplier<UploadImages> { object : UploadImages by FakeImages(storage) { override val variantPrefix = variants } })
            .withPropertyValues("skeleton.storage.web.public.enabled=true", "skeleton.storage.web.public.secret=public-secret-for-tests-0123456789abcdef", "skeleton.storage.web.public.base-url=/files")
        runner("uploads/variants").run { assertThat(it).hasFailed() }
        runner("uploads").run { assertThat(it).hasFailed() }
        runner("public").run { assertThat(it).hasFailed() }
        runner("variants").run { assertThat(it).hasNotFailed() }
    }
}

/** 목록만 흉내 낸다 - 모든 객체가 [old] 에 만들어진 것으로 보인다 */
private class AgedListing(private val inner: FakePresignedStorage, private val old: Instant) : PresignedStorage by inner {
    override fun list(request: ListObjectsRequest) = ListedObjects(inner.objects.keys.filter { it.startsWith(request.prefix) }.map { StoredObjectSummary(ObjectKey(it), lastModified = old) })
}
