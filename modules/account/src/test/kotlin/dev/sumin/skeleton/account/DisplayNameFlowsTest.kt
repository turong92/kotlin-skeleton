package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.AccountProperties.DisplayName.Fallback
import dev.sumin.skeleton.account.AccountProperties.DisplayName.Uniqueness
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 닉네임 방식(NONE · UNIQUE · TAGGED) · 필수 · 자동 닉네임 · 예약어가 가입 · 소셜 · 프로필 수정에서 어떻게 동작하는지 */
class DisplayNameFlowsTest {
    private val PW = ReauthInput("tangerine-42-moon")

    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }
    private object Magic : SignInMethod {
        override val code = "magic_link"
        override fun normalize(subject: String) = subject.trim().lowercase()
        override val provesEmail = true
    }

    private fun harness(
        uniqueness: Uniqueness = Uniqueness.NONE,
        required: Boolean = false,
        fallback: Fallback = Fallback.NONE,
        reserved: List<String> = emptyList(),
        seeds: List<AccountProperties.SeedAccount> = emptyList(),
        emailVerification: Boolean = true,
        changeLimit: Int = 10,
    ) = AccountHarness(
        AccountProperties(
            displayName = AccountProperties.DisplayName(uniqueness, required, fallback, reserved, changeLimit = changeLimit),
            social = AccountProperties.Social(signUp = true), seed = AccountProperties.Seed(seeds),
            signUp = AccountProperties.SignUp(emailVerification = emailVerification),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )

    private fun AccountHarness.signedUp(email: String, name: String?): Account {
        signUp(email, displayName = name); verify(email)
        return repo.findByEmail(Emails.normalize(email))!!
    }

    private fun AccountHarness.sso() = AccountSignInService(core, SignInMethodRegistry(listOf(PasswordSignInMethod(), Google, Magic)))
    private fun proof(method: String, subject: String, name: String?, email: String? = null) =
        SignInProof(method, subject, email, email != null, name, "ko", "203.0.113.1", true)

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    // ---- NONE (default)

    @Test
    fun `by default nicknames may repeat and there is no tag - exactly as before this feature`() {
        val h = harness()
        val a = h.signedUp("a@example.com", "Ann")
        val b = h.signedUp("b@example.com", "Ann")
        assertEquals("Ann", a.displayName); assertEquals("Ann", b.displayName)
        assertNull(h.profile.me(a.id).displayTag); assertNull(h.profile.me(b.id).displayTag)
        assertNull(a.displayTag, "NONE stores no tag, so the unique (key, tag) index never fires")
        assertEquals("ann", a.displayNameKey, "the key is always stored - switching the mode later needs no recomputation")
    }

    @Test
    fun `an account with no nickname stays without one by default`() {
        val h = harness()
        val a = h.signedUp("a@example.com", null)
        assertNull(a.displayName); assertNull(a.displayNameKey)
    }

    // ---- required-on-sign-up

    @Test
    fun `a required nickname that is missing is a 400 on the displayName field - before anything that depends on the address`() {
        val h = harness(required = true)
        val taken = h.signedUp("taken@example.com", "Someone")
        val sent = h.mailCount()
        listOf("fresh@example.com", taken.email!!).forEach { email ->
            listOf(null, "", "   ").forEach { blank ->
                val e = assertFailsWith<FieldValidationException> { h.signUp(email, displayName = blank) }
                assertEquals("displayName", e.field); assertEquals("Required", e.fieldCode)
            }
        }
        assertEquals(sent, h.mailCount(), "no mail, no attempt for a refused request - the same for a new and a registered address")
    }

    @Test
    fun `an unfit nickname is refused the same way on sign-up and on profile update, for a new and a registered address`() {
        val h = harness()
        val a = h.signedUp("a@example.com", "Ann")
        listOf("fresh@example.com", a.email!!).forEach { email ->
            assertEquals("Pattern", assertFailsWith<FieldValidationException> { h.signUp(email, displayName = "bad#name") }.fieldCode)
            assertEquals("Size", assertFailsWith<FieldValidationException> { h.signUp(email, displayName = "x".repeat(61)) }.fieldCode)
        }
        assertEquals("Pattern", assertFailsWith<FieldValidationException> { h.profile.update(a.id, ProfileChange(displayName = "a​b")) }.fieldCode)
        assertEquals("Ann", h.profile.me(a.id).displayName)
    }

    @Test
    fun `with email verification off the same checks run first and a duplicate address is still the 409 that app chose`() {
        val h = harness(required = true, emailVerification = false)
        assertEquals("displayName", assertFailsWith<FieldValidationException> { h.signUp("a@example.com", displayName = null) }.field)
        h.signUp("a@example.com", displayName = "Ann")
        assertEquals("ACCOUNT.EMAIL_TAKEN", code { h.signUp("a@example.com", displayName = "Bob") })
    }

    // ---- UNIQUE

    @Test
    fun `UNIQUE - the request never says a nickname is used, the verification that would create the clash is the 409`() {
        val h = harness(Uniqueness.UNIQUE)
        h.signedUp("a@example.com", "Ann")
        val outcome = h.signUp("b@example.com", displayName = "ANN")
        assertEquals(SignUpStatus.VERIFICATION_SENT, outcome.status)
        val refused = assertFailsWith<ApplicationException> { h.verify("b@example.com") }
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", refused.errorCode.code); assertEquals(409, refused.status.value())
        assertNull(h.repo.findByEmail("b@example.com"), "no account was created")
    }

    @Test
    fun `UNIQUE - width and case variants clash, a free name works, a tag is not shown`() {
        val h = harness(Uniqueness.UNIQUE)
        val a = h.signedUp("a@example.com", "Ann")
        assertEquals(DisplayNames.NO_TAG, a.displayTag)
        assertNull(h.profile.me(a.id).displayTag, "UNIQUE has no tag to show")
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.signedUp("b@example.com", "Ａｎｎ") })
        assertEquals("Bob", h.signedUp("c@example.com", "Bob").displayName)
    }

    @Test
    fun `UNIQUE - renaming onto a taken nickname is a 409 and changes nothing, renaming your own case is fine`() {
        val h = harness(Uniqueness.UNIQUE)
        h.signedUp("a@example.com", "Ann")
        val b = h.signedUp("b@example.com", "Bob")
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.profile.update(b.id, ProfileChange(displayName = "ann", locale = "en")) })
        val me = h.profile.me(b.id)
        assertEquals("Bob", me.displayName); assertEquals(b.locale, me.locale, "a refused update changes nothing else either")
        assertEquals("BOB", h.profile.update(b.id, ProfileChange(displayName = "BOB")).displayName)
        assertEquals("Bobby", h.profile.update(b.id, ProfileChange(displayName = "Bobby")).displayName)
        assertEquals("Bob", h.signedUp("c@example.com", "Bob").displayName, "the old name was released")
    }

    @Test
    fun `UNIQUE - a nickname is held while the account is in its deletion grace and released once it is erased`() {
        val h = harness(Uniqueness.UNIQUE)
        val a = h.signedUp("a@example.com", "Ann")
        h.deletion.delete(a.id, PW, null)
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.signedUp("b@example.com", "Ann") }, "DELETED still holds the name")
        h.time.advance(Duration.ofDays(31))
        assertEquals(1, h.purge.purgeDue())
        val erased = h.repo.findById(a.id)!!
        assertNull(erased.displayNameKey); assertNull(erased.displayTag)
        assertEquals("Ann", h.signedUp("b@example.com", "Ann").displayName)
    }

    // ---- I-5: a name clash at verification does not burn the attempt

    @Test
    fun `UNIQUE - a clash at verification keeps the attempt alive, the same code with another nickname finishes the sign-up`() {
        val h = harness(Uniqueness.UNIQUE)
        h.signedUp("a@example.com", "Ann")
        h.signUp("b@example.com", displayName = "ann")
        repeat(7) { assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.verify("b@example.com") }, "the code was right every time - more tries than max-attempts and still a 409, never CODE_EXPIRED") }
        val wrong = assertFailsWith<CodeInvalidException> { h.registration.verifyEmail(h.signUpIds.getValue("b@example.com"), "000000".takeIf { it != h.lastCode("b@example.com") } ?: "000001", "203.0.113.1") }
        assertEquals(AccountProperties.Verification().maxAttempts - 1, (wrong.data as Map<*, *>)["attemptsLeft"], "the attempts a correct code spent were given back, a wrong guess still costs one")
        h.verify("b@example.com", displayName = "Bea")
        assertEquals("Bea", h.repo.findByEmail("b@example.com")!!.displayName)
    }

    @Test
    fun `a nickname sent with the verification replaces the one from the sign-up, is checked by the same rules and burns nothing when refused`() {
        val h = harness(Uniqueness.UNIQUE, reserved = listOf("admin"))
        h.signUp("b@example.com", displayName = "Bob")
        val bad = assertFailsWith<FieldValidationException> { h.verify("b@example.com", displayName = "bad#name") }
        assertEquals("Pattern", bad.fieldCode)
        assertEquals("Reserved", assertFailsWith<FieldValidationException> { h.verify("b@example.com", displayName = "Admin") }.fieldCode)
        h.verify("b@example.com", displayName = "  Robert  ")
        assertEquals("Robert", h.repo.findByEmail("b@example.com")!!.displayName, "cleaned like any nickname, and it wins over the sign-up's")
    }

    // ---- S-5a: a limit on nickname changes

    @Test
    fun `changing the nickname is limited per account - a no-op and a refused name use none of it`() {
        val h = harness(changeLimit = 3)
        val a = h.signedUp("a@example.com", "Ann")
        repeat(5) { h.profile.update(a.id, ProfileChange(displayName = "Ann")) }
        repeat(5) { assertFailsWith<FieldValidationException> { h.profile.update(a.id, ProfileChange(displayName = "bad#name")) } }
        listOf("Bea", "Cy", "Di").forEach { h.profile.update(a.id, ProfileChange(displayName = it)) }
        val limited = assertFailsWith<ApplicationException> { h.profile.update(a.id, ProfileChange(displayName = "Ed")) }
        assertEquals("ACCOUNT.RATE_LIMITED", limited.errorCode.code)
        assertEquals("Di", h.profile.me(a.id).displayName)
        h.profile.update(a.id, ProfileChange(locale = "en"))   // locale and time zone are not nicknames
        h.time.advance(Duration.ofDays(1).plusSeconds(1))
        assertEquals("Ed", h.profile.update(a.id, ProfileChange(displayName = "Ed")).displayName)
        val other = h.signedUp("o@example.com", "Oz")
        assertEquals("Pi", h.profile.update(other.id, ProfileChange(displayName = "Pi")).displayName, "the limit is per account")
    }

    // ---- TAGGED

    @Test
    fun `TAGGED - the same nickname gets different four digit tags and the response shows the tag`() {
        val h = harness(Uniqueness.TAGGED)
        val tags = (1..5).map { h.signedUp("u$it@example.com", "Ann").displayTag!! }
        assertTrue(tags.all { Regex("^[0-9]{4}$").matches(it) && it != "0000" }, tags.toString())
        assertEquals(5, tags.toSet().size)
        assertEquals(tags[0], h.profile.me(h.repo.findByEmail("u1@example.com")!!.id).displayTag)
    }

    @Test
    fun `TAGGED - changing the nickname draws a new tag, changing only its case keeps it`() {
        val h = harness(Uniqueness.TAGGED, changeLimit = 0)   // 0 = no limit — this test renames 40 times
        val a = h.signedUp("a@example.com", "Ann")
        val kept = h.profile.update(a.id, ProfileChange(displayName = "ANN"))
        assertEquals("ANN", kept.displayName); assertEquals(a.displayTag, kept.displayTag)
        val others = (1..40).count { n ->
            val moved = h.profile.update(a.id, ProfileChange(displayName = "Bob$n"))
            assertTrue(Regex("^[0-9]{4}$").matches(moved.displayTag!!))
            moved.displayTag != a.displayTag
        }
        assertTrue(others > 30, "a new random tag is drawn per new name")
    }

    @Test
    fun `TAGGED - a clash on a random tag is retried, a nearly full nickname still finds its last free tag`() {
        val h = harness(Uniqueness.TAGGED)
        val now = h.time.now()
        (1..9999).filter { it != 4242 }.forEach { n ->
            assertTrue(h.repo.insert(Account("acc_f$n", "f$n@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "Ann", null, null, now, now, displayNameKey = "ann", displayTag = DisplayNames.tag(n)), emptyList()))
        }
        assertEquals("4242", h.signedUp("last@example.com", "Ann").displayTag)
    }

    @Test
    fun `TAGGED - when all 9999 tags of a nickname are taken the 409 says so, on sign-up and on rename`() {
        val h = harness(Uniqueness.TAGGED)
        val now = h.time.now()
        (1..9999).forEach { n ->
            assertTrue(h.repo.insert(Account("acc_f$n", "f$n@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "Ann", null, null, now, now, displayNameKey = "ann", displayTag = DisplayNames.tag(n)), emptyList()))
        }
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.signedUp("late@example.com", "Ann") })
        val bob = h.signedUp("bob@example.com", "Bob")
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.profile.update(bob.id, ProfileChange(displayName = "Ann")) })
        assertEquals("Bob", h.profile.me(bob.id).displayName)
    }

    // ---- fallback

    @Test
    fun `GENERATED - accounts created without a nickname get user dash six hex, never a piece of the email`() {
        val h = harness(fallback = Fallback.GENERATED)
        val email = "secret.person@example.com"
        val a = h.signedUp(email, null)
        assertTrue(Regex("^user-[0-9a-f]{6}$").matches(a.displayName!!), a.displayName)
        assertTrue(!a.displayName!!.contains("secret") && !a.displayName!!.contains("person"))
        assertEquals(a.displayName!!.lowercase(), a.displayNameKey)
    }

    @Test
    fun `GENERATED - a social sign-in without a name and a magic link sign-up both get one`() {
        val h = harness(fallback = Fallback.GENERATED)
        val social = h.sso().signIn(proof("google", "g-1", null))!!
        val magic = h.sso().signIn(proof("magic_link", "m@example.com", null, "m@example.com"))!!
        listOf(social.accountId, magic.accountId).forEach { id ->
            assertTrue(Regex("^user-[0-9a-f]{6}$").matches(h.repo.findById(id)!!.displayName!!))
        }
    }

    @Test
    fun `a provider name is kept when it fits and fixed when it does not, never refused`() {
        val h = harness()
        val ok = h.sso().signIn(proof("google", "g-1", "Hana Kim"))!!
        val unfit = h.sso().signIn(proof("google", "g-2", "ha#na​@x"))!!
        assertEquals("Hana Kim", h.repo.findById(ok.accountId)!!.displayName)
        assertEquals("hanax", h.repo.findById(unfit.accountId)!!.displayName)
    }

    @Test
    fun `UNIQUE - a provider name that is taken does not stop the sign-up, the account gets a generated name or none`() {
        listOf(Fallback.GENERATED to true, Fallback.NONE to false).forEach { (fallback, expectName) ->
            val h = harness(Uniqueness.UNIQUE, fallback = fallback)
            h.signedUp("a@example.com", "Hana")
            val social = h.sso().signIn(proof("google", "g-1", "hana"))!!
            val row = h.repo.findById(social.accountId)!!
            if (expectName) assertTrue(Regex("^user-[0-9a-f]{6}$").matches(row.displayName!!)) else assertNull(row.displayName)
        }
    }

    // ---- reserved

    @Test
    fun `reserved words are refused on sign-up and rename, an administrator account may keep them`() {
        val h = harness(reserved = listOf("admin", "운영자"))
        assertEquals("Reserved", assertFailsWith<FieldValidationException> { h.signUp(displayName = "Ａdmin") }.fieldCode)
        val ann = h.signedUp("a@example.com", "Ann")
        assertEquals("Reserved", assertFailsWith<FieldValidationException> { h.profile.update(ann.id, ProfileChange(displayName = "운 영 자")) }.fieldCode)
        h.repo.grantRole(ann.id, "ADMIN", h.time.now())
        assertEquals("Admin", h.profile.update(ann.id, ProfileChange(displayName = "Admin")).displayName)
    }

    @Test
    fun `a reserved word cannot get in as a provider name - it is dropped like a taken one`() {
        listOf(Fallback.GENERATED to true, Fallback.NONE to false).forEach { (fallback, expectName) ->
            val h = harness(reserved = listOf("admin", "운영자"), fallback = fallback)
            val social = h.sso().signIn(proof("google", "g-1", "Ａdmin"))!!
            val row = h.repo.findById(social.accountId)!!
            if (expectName) assertTrue(Regex("^user-[0-9a-f]{6}$").matches(row.displayName!!), row.displayName) else assertNull(row.displayName)
            val ok = h.sso().signIn(proof("google", "g-2", "Administrator"))!!
            assertEquals("Administrator", h.repo.findById(ok.accountId)!!.displayName, "only the listed words, not their neighbours")
        }
    }

    @Test
    fun `UNIQUE - a name with an invisible variation selector is the same name as the plain one`() {
        val h = harness(Uniqueness.UNIQUE)
        h.signedUp("a@example.com", "수민")
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.signUp("b@example.com", displayName = "수민\uFE0F"); h.verify("b@example.com") })
        val c = h.signedUp("c@example.com", "Lee")
        assertEquals("ACCOUNT.DISPLAY_NAME_TAKEN", code { h.profile.update(c.id, ProfileChange(displayName = "수민\uFE0F")) })
        assertEquals("👩‍💻", h.signedUp("d@example.com", "👩‍💻").displayName, "an emoji sequence is a fine name")
    }

    @Test
    fun `seed accounts are not subject to the reserved list and a tagged seed gets its tag`() {
        val h = harness(
            Uniqueness.TAGGED, reserved = listOf("admin"),
            seeds = listOf(AccountProperties.SeedAccount(id = "acc_admin", email = "admin@example.com", password = "password", roles = setOf("ADMIN"), displayName = "Admin")),
        )
        AccountSeeder(h.core).seed()
        val row = h.repo.findById("acc_admin")!!
        assertEquals("Admin", row.displayName)
        assertTrue(Regex("^[0-9]{4}$").matches(row.displayTag!!))
        AccountSeeder(h.core).seed()
        assertEquals(row.displayTag, h.repo.findById("acc_admin")!!.displayTag, "seeding twice changes nothing")
    }

    // ---- the author directory the account module offers

    @Test
    fun `the author directory names active suspended and grace accounts, not erased ones, in one repository call`() {
        val h = harness(Uniqueness.TAGGED)
        val active = h.signedUp("a@example.com", "Ann")
        val suspended = h.signedUp("s@example.com", "Sue")
        h.repo.update(suspended.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        val gone = h.signedUp("g@example.com", "Gus")
        h.deletion.delete(gone.id, PW, null)
        h.time.advance(Duration.ofDays(31)); h.purge.purgeDue()
        val leaving = h.signedUp("l@example.com", "Lee")
        h.deletion.delete(leaving.id, PW, null)
        h.callLog.calls.clear()
        val cards = AccountAuthorDirectory(h.repo).resolve(listOf(active.id, suspended.id, leaving.id, gone.id, "acc_nobody"), dev.sumin.skeleton.common.author.AuthorContext("board", "general"))
        assertEquals(1, h.callLog.calls.count { it.second == "namesOf" }, "one batched lookup, not one per id")
        assertEquals("Ann", cards.getValue(active.id).name); assertEquals(h.repo.findById(active.id)!!.displayTag, cards.getValue(active.id).tag)
        assertEquals("Sue", cards.getValue(suspended.id).name, "the row is alive, so the name stays")
        assertEquals("Lee", cards.getValue(leaving.id).name, "during the grace the name stays")
        assertNull(cards[gone.id]?.name, "an erased account has no name")
        assertNull(cards["acc_nobody"])
        assertNotNull(cards[active.id])
    }

    @Test
    fun `the author directory asks nothing for no ids`() {
        val h = harness()
        h.callLog.calls.clear()
        assertTrue(AccountAuthorDirectory(h.repo).resolve(emptyList(), dev.sumin.skeleton.common.author.AuthorContext("board")).isEmpty())
        assertEquals(0, h.callLog.calls.count { it.second == "namesOf" })
    }

    @Test
    fun `TAGGED - many sign-ups of one nickname at the same time all get different tags`() {
        val h = harness(Uniqueness.TAGGED)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val results = (1..60).map { n -> pool.submit<String?> { h.core.insertNamed(
                Account("acc_p$n", "p$n@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, h.time.now(), h.time.now()), emptyList(), "Ann", strict = true,
            ) { h.repo.insert(it, emptyList()) }.account.displayTag } }.map { it.get() }
            assertEquals(60, results.toSet().size, results.toString())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `two accounts never share a key and tag pair - the repository itself refuses it`() {
        val h = harness(Uniqueness.UNIQUE)
        val now = h.time.now()
        fun acc(id: String, tag: String?) = Account(id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "Ann", null, null, now, now, displayNameKey = "ann", displayTag = tag)
        assertTrue(h.repo.insert(acc("acc_1", "0000"), emptyList()))
        assertTrue(!h.repo.insert(acc("acc_2", "0000"), emptyList()))
        assertTrue(h.repo.insert(acc("acc_3", null), emptyList()), "a missing tag (NONE) never collides")
        assertTrue(h.repo.insert(acc("acc_4", null), emptyList()))
        assertEquals(SetNameResult.TAKEN, h.repo.setDisplayName("acc_3", "Ann", "ann", "0000", now))
        assertEquals(SetNameResult.DONE, h.repo.setDisplayName("acc_3", "Ann", "ann", "0001", now))
        assertEquals(setOf("0000", "0001"), h.repo.displayTagsOf("ann"))
    }
}
