package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountProperties.DisplayName.Fallback
import dev.sumin.skeleton.account.AccountProperties.DisplayName.Uniqueness
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `skeleton.account.social.import-display-name` — 제공자가 준 이름(Google 실명 · Kakao 닉네임 · OIDC name)을 닉네임으로 가져올지 */
class SocialDisplayNameImportTest {
    private val providers = listOf("google", "kakao", "line")

    private fun harness(import: Boolean?, uniqueness: Uniqueness = Uniqueness.NONE, fallback: Fallback = Fallback.NONE, reserved: List<String> = emptyList()): Pair<AccountHarness, AccountOAuthProvisioningPolicy> {
        val social = if (import == null) AccountProperties.Social(signUp = true) else AccountProperties.Social(signUp = true, importDisplayName = import)
        val h = AccountHarness(
            AccountProperties(
                social = social, mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
                displayName = AccountProperties.DisplayName(uniqueness = uniqueness, fallback = fallback, reserved = reserved),
            ),
        )
        val registry = SignInMethodRegistry(listOf(PasswordSignInMethod()) + providers.map { SocialSignInMethod(it) })
        return h to AccountOAuthProvisioningPolicy(AccountSignInService(h.core, registry), social)
    }

    private fun profile(provider: String, id: String, name: String?) = OAuthUserProfile(provider, id, null, null, name)

    @Test
    fun `the module default keeps today's behaviour - the provider name becomes the nickname`() {
        val (h, policy) = harness(import = null)
        providers.forEach { p ->
            val id = policy.resolveOrCreateAccount(profile(p, "$p-1", "Hana Kim"))!!
            assertEquals("Hana Kim", h.repo.findById(id)!!.displayName, p)
        }
    }

    @Test
    fun `import false - Google Kakao and OIDC sign-ups get no name at all`() {
        val (h, policy) = harness(import = false)
        providers.forEach { p ->
            val id = policy.resolveOrCreateAccount(profile(p, "$p-1", "Hana Kim"))!!
            val row = h.repo.findById(id)!!
            assertNull(row.displayName, p); assertNull(row.displayNameKey, p); assertNull(row.displayTag, p)
        }
    }

    @Test
    fun `import false does not stop the display-name fallback - GENERATED still names the account`() {
        val (h, policy) = harness(import = false, fallback = Fallback.GENERATED)
        val id = policy.resolveOrCreateAccount(profile("google", "g-1", "Hana Kim"))!!
        val name = h.repo.findById(id)!!.displayName!!
        assertTrue(Regex("^user-[0-9a-f]{6}$").matches(name), name)
    }

    @Test
    fun `import false never meets UNIQUE or reserved - a name that would clash or is reserved is not even looked at`() {
        val (h, policy) = harness(import = false, uniqueness = Uniqueness.UNIQUE, reserved = listOf("admin"))
        val a = policy.resolveOrCreateAccount(profile("google", "g-1", "Hana"))!!
        val b = policy.resolveOrCreateAccount(profile("kakao", "k-1", "Hana"))!!
        val c = policy.resolveOrCreateAccount(profile("line", "l-1", "Admin"))!!
        listOf(a, b, c).forEach { assertNull(h.repo.findById(it)!!.displayName) }
    }

    @Test
    fun `import false - signing in again does not write the provider name onto the account`() {
        val (h, policy) = harness(import = false)
        val id = policy.resolveOrCreateAccount(profile("google", "g-1", "Hana Kim"))!!
        assertEquals(id, policy.resolveOrCreateAccount(profile("google", "g-1", "Hana Renamed")))
        assertNull(h.repo.findById(id)!!.displayName)
    }

    @Test
    fun `import false leaves a name the user chose alone on re-login`() {
        val (h, policy) = harness(import = false)
        val id = policy.resolveOrCreateAccount(profile("google", "g-1", "Hana Kim"))!!
        h.profile.update(id, dev.sumin.skeleton.account.ProfileChange(displayName = "Mine"))
        policy.resolveOrCreateAccount(profile("google", "g-1", "Hana Kim"))
        assertEquals("Mine", h.repo.findById(id)!!.displayName)
    }
}
