package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import java.time.Duration
import java.time.Instant
import java.time.Duration as D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 회원 업로드 정리 - 데이터 손실 회귀: 사용 중인 키 · 나이가 안 찬 키 · 남의 영역은 지우지 않는다 */
class StorageMemberCleanupTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val old = now.minus(Duration.ofDays(10))
    private val fresh = now.minus(Duration.ofDays(2))
    private val guestSecret = "guest-secret-for-tests-0123456789abcdef"
    private val scheme = StorageKeyScheme(
        StorageWebProperties(
            guest = StorageGuestProperties(enabled = true, secret = guestSecret),
            cleanup = StorageCleanupProperties(enabled = true, minAge = Duration.ofDays(7), batchSize = 10),
        ),
    )

    private class ListingStorage(private val objects: List<StoredObjectSummary>) : PresignedStorage by FakePresignedStorage() {
        val deleted = mutableListOf<String>()
        override fun list(request: ListObjectsRequest): ListedObjects {
            val all = objects.filter { it.key.value.startsWith(request.prefix) }
            val from = request.continuationToken?.toInt() ?: 0
            val more = from + 3 < all.size
            return ListedObjects(all.drop(from).take(3), nextContinuationToken = if (more) (from + 3).toString() else null, truncated = more)
        }
        override fun deleteAll(keys: Collection<ObjectKey>) { deleted += keys.map { it.value } }
    }

    private class RecordingImages : UploadImages {
        val forgotten = mutableListOf<String>()
        override val variantPrefix = "variants"
        override fun status(privateKey: String): UploadImageStatus? = null
        override fun signedVariantUrl(privateKey: String, variant: String, ttl: D): String? = null
        override fun ensure(privateKey: String) = Unit
        override fun forget(privateKeys: Collection<String>) { forgotten += privateKeys }
    }

    @Test
    fun `deletes only old keys that no usage claims - review photos and board attachments stay, young and foreign areas stay`() {
        val orphan = scheme.issue("acc_a", "a.png")
        val reviewPhoto = scheme.issue("acc_a", "b.png")
        val boardAttachment = scheme.issue("acc_b", "c.png")
        val young = scheme.issue("acc_a", "d.png")
        val guestOrphan = scheme.issueGuest("image/png")
        val outside = "variants/abc/thumb.jpg"
        val storage = ListingStorage(
            listOf(orphan to old, reviewPhoto to old, boardAttachment to old, young to fresh, guestOrphan to old, outside to old)
                .map { (k, t) -> StoredObjectSummary(ObjectKey(k), lastModified = t) },
        )
        val images = RecordingImages()
        val reviews = UploadedKeyUsage { keys -> keys.filter { it == reviewPhoto }.toSet() }
        val board = UploadedKeyUsage { keys -> keys.filter { it == boardAttachment }.toSet() }
        val cleanup = StorageMemberCleanup(storage, scheme, { dev.sumin.skeleton.common.upload.UploadedKeyUsages.union(listOf(reviews, board)) }, TimeProvider.fixed(now), null) { images }

        assertEquals(1, cleanup.run())
        assertEquals(listOf(orphan), storage.deleted)
        assertEquals(listOf(orphan), images.forgotten, "the variants and status row of the deleted original go with it")
    }

    @Test
    fun `without a usage source nothing is deleted`() {
        val storage = ListingStorage(listOf(StoredObjectSummary(ObjectKey(scheme.issue("acc_a", "a.png")), lastModified = old)))
        assertEquals(0, StorageMemberCleanup(storage, scheme, { null }, TimeProvider.fixed(now)).run())
        assertTrue(storage.deleted.isEmpty())
    }
}
