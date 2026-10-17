package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageGuestKeysTest {
    private val secret = "guest-secret-for-tests-0123456789abcdef"
    private fun scheme(enabled: Boolean = true, owner: StorageKeyOwner = StorageKeyOwner.ACCOUNT_ID, guestSecret: String = secret) = StorageKeyScheme(
        StorageWebProperties(
            keyOwner = owner, keySecret = "x".repeat(32),
            guest = StorageGuestProperties(enabled = enabled, secret = if (enabled) guestSecret else "", cleanup = StorageGuestProperties.GuestCleanup(enabled = true)),
        ),
    )

    @Test
    fun `an issued guest key checks out, a forged mac or another secret does not`() {
        val key = scheme().issueGuest("image/png")
        assertTrue(scheme().isGuestKey(key))
        val (head, tail) = key.substringBeforeLast('/') to key.substringAfterLast('/')
        val flipped = head.dropLast(1) + (if (head.last() == '0') '1' else '0') + "/" + tail
        assertFalse(scheme().isGuestKey(flipped), "one changed mac character")
        assertFalse(scheme(guestSecret = "another-guest-secret-0123456789abcdef").isGuestKey(key), "a key from another project's secret")
        assertFalse(scheme().isGuestKey(key.replace("file.png", "file.svg")), "the tail is limited to the image names")
        assertFalse(scheme(enabled = false).isGuestKey(key), "off: no key counts")
    }

    @Test
    fun `member keys are never guest keys, and nobody owns the guest prefix - not even an account named g`() {
        val s = scheme()
        val member = s.issue("acc_a", "a.png")
        assertFalse(s.isGuestKey(member))
        assertFalse(s.isGuestKey("uploads/acc_a/whatever/a.png"))
        assertFalse(s.isGuestKey("uploads/g/anything/file.png"))
        val guest = s.issueGuest("image/webp")
        assertFalse(s.owns("g", guest))
        assertFalse(s.owns("acc_a", guest))
        assertTrue(s.owns("acc_a", member))
        val opaque = scheme(owner = StorageKeyOwner.OPAQUE)
        assertFalse(opaque.isGuestKey(opaque.issue("acc_a", "a.png")))
        assertFalse(opaque.owns("acc_a", opaque.issueGuest("image/gif")))
    }

    @Test
    fun `the guest settings cannot open svg or start without a long secret`() {
        assertEquals(setOf("image/jpeg", "image/png", "image/webp", "image/gif"), StorageGuestProperties().allowedContentTypes)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { StorageGuestProperties(allowedContentTypes = setOf("image/svg+xml")) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { StorageGuestProperties(enabled = true, secret = "short") }
    }

    /** 목록과 삭제만 흉내 낸다 */
    private class ListingStorage(private val objects: List<StoredObjectSummary>) : PresignedStorage by FakePresignedStorage() {
        val deleted = mutableListOf<String>()
        /** 세 개씩 쪽으로 나눠 준다 - 이어 읽기를 시험한다 */
        override fun list(request: ListObjectsRequest): ListedObjects {
            val all = objects.filter { it.key.value.startsWith(request.prefix) }
            val from = request.continuationToken?.toInt() ?: 0
            val page = all.drop(from).take(3)
            val more = from + 3 < all.size
            return ListedObjects(page, nextContinuationToken = if (more) (from + 3).toString() else null, truncated = more)
        }
        override fun deleteAll(keys: Collection<ObjectKey>) { deleted += keys.map { it.value } }
    }

    @Test
    fun `cleanup deletes only old, unattached, genuine guest keys`() {
        val s = scheme()
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val old = now.minus(Duration.ofHours(30))
        val fresh = now.minus(Duration.ofHours(2))
        val orphan = s.issueGuest("image/png")
        val attached = s.issueGuest("image/png")
        val young = s.issueGuest("image/png")
        val foreign = "uploads/g/${java.util.UUID.randomUUID()}.${"0".repeat(32)}/file.png"   // 꼴은 맞지만 MAC 이 틀린 남의 파일
        val storage = ListingStorage(
            listOf(orphan to old, attached to old, young to fresh, foreign to old).map { (k, t) -> StoredObjectSummary(ObjectKey(k), lastModified = t) },
        )
        val cleanup = StorageGuestCleanup(storage, s, { UploadedKeyUsage { keys -> keys.filter { it == attached }.toSet() } }, TimeProvider.fixed(now))
        assertEquals(1, cleanup.run())
        assertEquals(listOf(orphan), storage.deleted, "attached, young and not-ours files stay")
    }

    @Test
    fun `without a usage source cleanup deletes nothing`() {
        val s = scheme()
        val old = Instant.parse("2026-10-16T00:00:00Z")
        val storage = ListingStorage(listOf(StoredObjectSummary(ObjectKey(s.issueGuest("image/png")), lastModified = old)))
        assertEquals(0, StorageGuestCleanup(storage, s, { null }, TimeProvider.fixed(Instant.parse("2026-10-18T12:00:00Z"))).run())
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `attached keys filling the front of the listing do not hide the orphans behind them`() {
        val s = scheme()
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val old = now.minus(Duration.ofHours(30))
        val attached = (1..7).map { s.issueGuest("image/png") }
        val orphans = (1..2).map { s.issueGuest("image/png") }
        val storage = ListingStorage((attached + orphans).map { StoredObjectSummary(ObjectKey(it), lastModified = old) })
        val small = StorageKeyScheme(StorageWebProperties(guest = StorageGuestProperties(enabled = true, secret = secret, cleanup = StorageGuestProperties.GuestCleanup(enabled = true, batchSize = 2))))
        // 비밀이 같아야 s 가 만든 키가 small 에서도 맞는다
        val cleanup = StorageGuestCleanup(storage, small, { UploadedKeyUsage { keys -> keys.filter { it in attached }.toSet() } }, TimeProvider.fixed(now))
        assertEquals(2, cleanup.run())
        assertEquals(orphans.toSet(), storage.deleted.toSet(), "the orphans past the attached ones are deleted")
    }
}
