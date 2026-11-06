package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.upload.PublicUploads
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 프로필 사진(아바타) 키: 설정 · 지움 · 그대로, 남의 키 거절, 공개 주소, 기능이 꺼진 앱 */
class ProfileAvatarTest {
    /** 키 `uploads/<계정 id>/<파일>` 만 그 계정의 것 - storage 의 `ACCOUNT_ID` 방식을 흉내 낸다 */
    private val ownership = AvatarKeyOwnership { accountId, key -> key.startsWith("uploads/$accountId/") }

    private class FakePublic : PublicUploads {
        val published = mutableSetOf<String>()
        val unpublished = mutableListOf<String>()
        override fun urlOf(privateKey: String) = "https://cdn.example.com/public/" + privateKey.substringAfterLast('/')
        override fun publicKeyOf(privateKey: String) = "public/" + privateKey.substringAfterLast('/')
        override fun publish(privateKey: String): Boolean { published += privateKey; return true }
        override fun unpublish(privateKey: String) { unpublished += privateKey; published -= privateKey }
        override fun listPublicKeys(): List<String> = emptyList()
        override fun deletePublicKey(publicKey: String) = Unit
    }

    private fun service(h: AccountHarness, avatars: AccountAvatars) =
        ProfileService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod())), avatars)

    private fun enabled(public: PublicUploads? = null) = AccountAvatars({ ownership }, { public })

    @Test
    fun `sets, keeps and clears the avatar key`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val profile = service(h, enabled())

        assertEquals(Optional.empty(), profile.me(account.id).avatarKey)   // 켜져 있고 아직 없다

        val key = "uploads/${account.id}/a.png"
        assertEquals(Optional.of(key), profile.update(account.id, ProfileChange(avatarKey = Optional.of(key))).avatarKey)

        // 필드 없음 = 그대로
        assertEquals(Optional.of(key), profile.update(account.id, ProfileChange(locale = "ko-KR")).avatarKey)

        // null = 지움
        assertEquals(Optional.empty(), profile.update(account.id, ProfileChange(avatarKey = Optional.empty())).avatarKey)
        assertNull(h.repo.findById(account.id)!!.avatarKey)
    }

    @Test
    fun `refuses someone else's key and changes nothing else`() {
        val h = AccountHarness()
        val ann = h.activeAccount()
        val profile = service(h, enabled())

        assertFailsWith<ApplicationException> { profile.update(ann.id, ProfileChange(locale = "ko-KR", avatarKey = Optional.of("uploads/other-account/a.png"))) }
        assertFailsWith<ApplicationException> { profile.update(ann.id, ProfileChange(avatarKey = Optional.of("  "))) }
        assertNull(h.repo.findById(ann.id)!!.avatarKey)
        assertNull(h.repo.findById(ann.id)!!.locale)   // 사진이 거절되면 다른 칸도 쓰지 않는다
    }

    @Test
    fun `without storage the avatar fields are absent and an avatarKey is refused`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val profile = ProfileService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod())))

        val me = profile.me(account.id)
        assertNull(me.avatarKey)
        assertNull(me.avatar)
        assertFailsWith<ApplicationException> { profile.update(account.id, ProfileChange(avatarKey = Optional.of("uploads/${account.id}/a.png"))) }
        assertFailsWith<ApplicationException> { profile.update(account.id, ProfileChange(avatarKey = Optional.empty())) }
    }

    @Test
    fun `public area on gives a public address and the replaced key's copy is removed`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val public = FakePublic()
        val profile = service(h, enabled(public))

        val first = "uploads/${account.id}/a.png"
        val me = profile.update(account.id, ProfileChange(avatarKey = Optional.of(first)))
        assertEquals("https://cdn.example.com/public/a.png", me.avatar?.url)
        assertTrue(first in public.published)

        val second = "uploads/${account.id}/b.png"
        profile.update(account.id, ProfileChange(avatarKey = Optional.of(second)))
        assertEquals(listOf(first), public.unpublished)

        profile.update(account.id, ProfileChange(avatarKey = Optional.empty()))
        assertEquals(listOf(first, second), public.unpublished)
        assertNull(profile.me(account.id).avatar)
    }

    @Test
    fun `public area off shows the key only`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val profile = service(h, enabled(null))

        val key = "uploads/${account.id}/a.png"
        val me = profile.update(account.id, ProfileChange(avatarKey = Optional.of(key)))
        assertEquals(Optional.of(key), me.avatarKey)
        assertNotNull(me.avatar)
        assertNull(me.avatar?.url)
    }

    @Test
    fun `in-use lookup sees live avatars and not erased ones`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val profile = service(h, enabled())
        val key = "uploads/${account.id}/a.png"
        profile.update(account.id, ProfileChange(avatarKey = Optional.of(key)))

        assertEquals(setOf(key), h.repo.avatarKeysInUse(listOf(key, "uploads/x/y.png")))
        assertEquals(emptySet(), h.repo.avatarKeysInUse(listOf("uploads/x/y.png")))
    }

    private fun withUsage(public: PublicUploads?, inUse: Set<String>) = AccountAvatars({ ownership }, { public }, usages = { listOf(object : UploadedKeyUsage { override val answers = setOf("author-profile"); override fun inUse(keys: Collection<String>) = keys.filter { it in inUse }.toSet() }) })

    @Test
    fun `a key another holder uses is refused, and its public copy is kept when this holder lets go`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val public = FakePublic()
        val taken = "uploads/${account.id}/profile.png"
        val profile = service(h, withUsage(public, setOf(taken)))

        assertFailsWith<ApplicationException> { profile.update(account.id, ProfileChange(avatarKey = Optional.of(taken))) }
        assertNull(h.repo.findById(account.id)!!.avatarKey)

        val avatars = withUsage(public, setOf(taken))
        avatars.retire(taken)
        assertTrue(public.unpublished.isEmpty(), "still used elsewhere - the copy stays")
        avatars.retire(taken, force = true)
        assertEquals(listOf(taken), public.unpublished)
    }

    @Test
    fun `a copy made while the key was replaced is taken back instead of reviving a removed picture`() {
        val public = FakePublic()
        val avatars = enabled(public)
        val key = "uploads/acc/a.png"

        val view = avatars.view(key) { false }
        assertNull(view!!.url)
        assertEquals(listOf(key), public.unpublished)
        assertEquals("https://cdn.example.com/public/a.png", avatars.view(key)!!.url)
    }

    @Test
    fun `retireOrThrow lets a failed removal through so the erasure retries, retire swallows it`() {
        val failing = object : PublicUploads by FakePublic() { override fun scoped(segment: String): PublicUploads = this; override fun unpublish(privateKey: String) = error("cdn down") }
        val avatars = enabled(failing)

        avatars.retire("uploads/acc/a.png")
        assertFailsWith<IllegalStateException> { avatars.retireOrThrow("uploads/acc/a.png", force = true) }
    }

    @Test
    fun `only images are accepted as a picture`() {
        val h = AccountHarness()
        val account = h.activeAccount()
        val profile = service(h, enabled())

        assertFailsWith<ApplicationException> { profile.update(account.id, ProfileChange(avatarKey = Optional.of("uploads/${account.id}/doc.pdf"))) }
        assertFailsWith<ApplicationException> { profile.update(account.id, ProfileChange(avatarKey = Optional.of("uploads/${account.id}/noext"))) }
    }

    @Test
    fun `a write copies again even when this instance remembers the key, and a board attachment does not keep the avatar copy alive`() {
        val public = FakePublic()
        val board = object : UploadedKeyUsage { override val answers = setOf("board"); override fun inUse(keys: Collection<String>) = keys.toSet() }
        val avatars = AccountAvatars({ ownership }, { public }, usages = { listOf(board) })
        val key = "uploads/acc/a.png"

        avatars.view(key)
        public.published.clear()   // 다른 인스턴스가 사본을 거뒀다 - 이 인스턴스의 기억은 낡았다
        avatars.publish(key)
        assertTrue(key in public.published, "the write path ignores the memory")

        avatars.retire(key)
        assertEquals(listOf(key), public.unpublished, "board uses the key but never the a/ copy")
    }
}
