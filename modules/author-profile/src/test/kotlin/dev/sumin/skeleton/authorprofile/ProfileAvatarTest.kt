package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountAvatars
import dev.sumin.skeleton.account.AvatarKeyOwnership
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.upload.PublicUploads
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 프로필마다 따로인 사진: 설정 · 지움 · 그대로, 남의 키 · 계정 id 가 든 키 · 이미 쓰는 키 거절, 이름과 함께일 때 원자성, 기능이 꺼진 앱 */
class ProfileAvatarTest {
    /** 키 `uploads/o/o1-<파일>`(계정 id 가 안 드러나는 OPAQUE 꼴)을 흉내 낸다 - acc_1 -> o1 */
    private val ownership = AvatarKeyOwnership { accountId, key -> key.startsWith("uploads/o/${accountId.replace("acc_", "o")}-") || key.startsWith("uploads/$accountId/") }

    private class FakePublic : PublicUploads {
        val unpublished = mutableListOf<String>()
        override fun urlOf(privateKey: String) = "https://cdn.example.com/public/" + privateKey.substringAfterLast('/')
        override fun publicKeyOf(privateKey: String) = "public/" + privateKey.substringAfterLast('/')
        override fun publish(privateKey: String) = true
        override fun unpublish(privateKey: String) { unpublished += privateKey }
        override fun listPublicKeys(): List<String> = emptyList()
        override fun deletePublicKey(publicKey: String) = Unit
    }

    private fun service(h: Harness, avatars: AccountAvatars) =
        AuthorProfileService(h.repository, { h.accounts }, h.names, h.limits, h.properties, h.time, avatars = { avatars })

    private fun enabled(public: PublicUploads? = null) = AccountAvatars({ ownership }, { public })

    @Test
    fun `sets, keeps and clears a picture without a name, and a name can ride along`() {
        val h = Harness()
        val service = service(h, enabled())
        val p = service.create("acc_1", "Moon")
        val key = "uploads/o/o1-a.png"

        assertEquals(key, service.update("acc_1", p.id, null, Optional.of(key)).avatarKey)
        assertEquals("Moon", h.repository.find(p.id)!!.name)
        // 필드 없음 = 그대로 (이름만 바꾼다)
        val renamed = service.update("acc_1", p.id, "Sun", null)
        assertEquals("Sun", renamed.name)
        assertEquals(key, renamed.avatarKey)
        // 이름과 사진을 함께
        val both = service.update("acc_1", p.id, "Star", Optional.of("uploads/o/o1-b.png"))
        assertEquals("Star", both.name)
        assertEquals("uploads/o/o1-b.png", both.avatarKey)
        // 지움
        assertNull(service.update("acc_1", p.id, null, Optional.empty()).avatarKey)
    }

    @Test
    fun `a key that contains the account id is refused, an account's own picture key is refused, another profile's key is refused`() {
        val h = Harness()
        val service = service(h, enabled())
        val a = service.create("acc_1", "Moon")
        val b = service.create("acc_1", "Sun")

        // 계정 id 가 든 키 - 키로 두 프로필이 이어진다
        assertFailsWith<ApplicationException> { service.update("acc_1", a.id, null, Optional.of("uploads/acc_1/a.png")) }

        // 이미 다른 프로필이 쓰는 키
        val shared = "uploads/o/o1-shared.png"
        service.update("acc_1", a.id, null, Optional.of(shared))
        assertFailsWith<ApplicationException> { service.update("acc_1", b.id, null, Optional.of(shared)) }

        // 계정의 사진으로 쓰는 키
        val accountKey = "uploads/o/o1-account.png"
        h.accounts.update("acc_1", AccountPatch(avatarKey = accountKey), h.time.now())
        assertFailsWith<ApplicationException> { service.update("acc_1", b.id, null, Optional.of(accountKey)) }
        assertNull(h.repository.find(b.id)!!.avatarKey)
    }

    @Test
    fun `someone else's key and someone else's profile are refused`() {
        val h = Harness()
        val service = service(h, enabled())
        val mine = service.create("acc_1", "Moon")
        val theirs = service.create("acc_2", "Sun")

        assertFailsWith<ApplicationException> { service.update("acc_1", mine.id, null, Optional.of("uploads/o/o2-a.png")) }
        assertFailsWith<AuthorProfileException> { service.update("acc_1", theirs.id, null, Optional.of("uploads/o/o1-a.png")) }
        assertNull(h.repository.find(mine.id)!!.avatarKey)
    }

    @Test
    fun `a refused name leaves the picture unchanged and a refused picture leaves the name unchanged`() {
        val h = Harness()
        val service = service(h, enabled())
        val p = service.create("acc_1", "Moon")

        assertFailsWith<FieldValidationException> { service.update("acc_1", p.id, "a#b", Optional.of("uploads/o/o1-a.png")) }
        assertNull(h.repository.find(p.id)!!.avatarKey)

        assertFailsWith<ApplicationException> { service.update("acc_1", p.id, "Sun", Optional.of("uploads/o/other-a.png")) }
        assertEquals("Moon", h.repository.find(p.id)!!.name)
    }

    @Test
    fun `without storage an avatarKey is refused and the plain rename still works`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")

        assertFailsWith<ApplicationException> { h.service.update("acc_1", p.id, null, Optional.of("uploads/o/o1-a.png")) }
        assertFailsWith<ApplicationException> { h.service.update("acc_1", p.id, null, Optional.empty()) }
        assertEquals("Sun", h.service.update("acc_1", p.id, "Sun", null).name)
    }

    @Test
    fun `replacing, clearing and deleting remove the old public copy, and the in-use lookup follows`() {
        val h = Harness()
        val public = FakePublic()
        val service = service(h, enabled(public))
        val p = service.create("acc_1", "Moon")
        val first = "uploads/o/o1-a.png"
        val second = "uploads/o/o1-b.png"

        service.update("acc_1", p.id, null, Optional.of(first))
        assertEquals(setOf(first), h.repository.avatarKeysInUse(listOf(first, second)))
        service.update("acc_1", p.id, null, Optional.of(second))
        assertEquals(listOf(first), public.unpublished)
        service.delete("acc_1", p.id)
        assertEquals(listOf(first, second), public.unpublished)
        assertTrue(h.repository.avatarKeysInUse(listOf(first, second)).isEmpty(), "a deleted profile's picture is not in use")
    }

    @Test
    fun `a failed public-copy removal makes the erasure listener throw so the account erasure is retried`() {
        val h = Harness()
        val failing = object : PublicUploads by FakePublic() { override fun scoped(segment: String): PublicUploads = this; override fun unpublish(privateKey: String) = error("cdn down") }
        val avatars = enabled(failing)
        val service = service(h, avatars)
        val p = service.create("acc_1", "Moon")
        service.update("acc_1", p.id, null, Optional.of("uploads/o/o1-a.png"))

        assertFailsWith<IllegalStateException> { AuthorProfileErasureListener(h.repository) { avatars }.erase(dev.sumin.skeleton.common.erasure.ErasureRequest("acc_1", "deleted:x")) }
        assertTrue(h.repository.find(p.id) != null, "nothing was erased before the failure")
    }
}
