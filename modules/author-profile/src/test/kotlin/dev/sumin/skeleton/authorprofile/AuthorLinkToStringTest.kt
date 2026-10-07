package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.authorprofile.web.AdminAuthorLinksResponse
import dev.sumin.skeleton.authorprofile.web.AdminAuthorProfileResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 계정 id 와 프로필 id 의 쌍이 우연한 로그 · 예외 메시지에 실리지 않게 — 두 응답 묶음의 toString 은 개수만 보인다 */
class AuthorLinkToStringTest {
    private val profile = AuthorProfile("prf_aaaa", "acc_bbbb", "acc_bbbb", "n", "n", null, AuthorProfileStatus.ACTIVE, null, T0, T0, null)

    @Test
    fun `AuthorLinks prints only the count`() {
        val text = AuthorLinks("acc_bbbb", listOf(profile)).toString()
        assertEquals("AuthorLinks(profiles=1)", text)
    }

    @Test
    fun `AdminAuthorLinksResponse prints only the count`() {
        val text = AdminAuthorLinksResponse("acc_bbbb", listOf(AdminAuthorProfileResponse("prf_aaaa", "n", null, "ACTIVE", T0, T0, null))).toString()
        assertEquals("AdminAuthorLinksResponse(profiles=1)", text)
        assertFalse("acc_bbbb" in text || "prf_aaaa" in text)
    }
}
