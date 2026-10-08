package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AddIdentityResult
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 한 계정에 같은 제공자 소셜은 하나 — 검사는 계정 행을 잠근 같은 트랜잭션 안에서 한다 (저장소가 판정, 서비스의 사전 검사는 친절일 뿐) */
class OneProviderPerAccountTest {
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }

    @Test
    fun `the store refuses a second subject of the same method on one account`() {
        val h = AccountHarness()
        val a = h.activeAccount()
        val now = h.time.now()
        assertEquals(AddIdentityResult.ADDED, h.repo.addIdentityIfEmailVerified(Identity("idn_g1", a.id, "google", "g-1", true, createdAt = now), null))
        assertEquals(AddIdentityResult.METHOD_EXISTS, h.repo.addIdentityIfEmailVerified(Identity("idn_g2", a.id, "google", "g-2", true, createdAt = now), null))
        assertEquals(AddIdentityResult.METHOD_EXISTS, h.repo.addIdentityIfEmailVerified(Identity("idn_g3", a.id, "google", "g-3", true, createdAt = now), a.emailVerified))
        assertEquals(listOf("g-1"), h.repo.identitiesOf(a.id).filter { it.method == "google" }.map { it.subject })
    }

    @Test
    fun `the service answers IDENTITY_EXISTS for it`() {
        val h = AccountHarness()
        val a = h.activeAccount()
        val ids = IdentityService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google)))
        ids.link(a.id, "google", "g-1", verified = true, expectEmailVerified = a.emailVerified)
        assertEquals("ACCOUNT.IDENTITY_EXISTS", assertFailsWith<ApplicationException> { ids.link(a.id, "google", "g-2", verified = true, expectEmailVerified = a.emailVerified) }.errorCode.code)
    }
}
