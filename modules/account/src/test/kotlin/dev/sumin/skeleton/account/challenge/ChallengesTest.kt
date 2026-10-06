package dev.sumin.skeleton.account.challenge

import dev.sumin.skeleton.account.MutableTime
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChallengesTest {
    private val time = MutableTime()
    private val store = InMemoryChallengeStore()
    private val key = ByteArray(32) { it.toByte() }
    private val challenges = Challenges(store, CodeHasher(key), time)
    private val ttl = Duration.ofMinutes(10)

    private fun open(purpose: String = ChallengePurposes.SIGN_UP, subject: String = "a@example.com", withHandle: Boolean = true, sessionId: String? = null) =
        challenges.open(purpose, subject, ttl, 5, sessionId = sessionId, withHandle = withHandle)

    @Test
    fun `a code is six decimal digits, uniformly random, with leading zeros kept`() {
        val codes = (1..3000).map { open().code }
        assertTrue(codes.all { it.matches(Regex("\\d{6}")) })
        assertTrue(codes.any { it.startsWith("0") }, "leading zeros must be possible (a numeric field would lose them)")
        assertTrue(codes.toSet().size > 2900, "codes must not repeat like a small counter")
        val firstDigits = codes.groupingBy { it[0] }.eachCount()
        assertEquals(10, firstDigits.size)
        assertTrue(firstDigits.values.all { it in 200..400 }, "first digit is uniform: $firstDigits")
    }

    @Test
    fun `the code is exactly the generator's nextInt(1_000_000) - no modulo, no other draw - so every value from 000000 to 999999 is equally likely`() {
        val bounds = mutableListOf<Int>()
        val drawn = ArrayDeque(listOf(0, 7, 999_999, 123_456))
        val fake = object : java.util.random.RandomGenerator {
            override fun nextLong(): Long = error("a code must not be derived from a raw 64-bit draw (modulo bias)")
            override fun nextInt(): Int = error("a code must not be derived from a raw 32-bit draw (modulo bias)")
            override fun nextInt(bound: Int): Int { bounds += bound; return drawn.removeFirst() }
            override fun nextBytes(bytes: ByteArray) { bytes.fill(1) }
        }
        val seeded = Challenges(InMemoryChallengeStore(), CodeHasher(key), time, fake)
        val codes = (1..4).map { seeded.open(ChallengePurposes.REAUTH, "acc_1", ttl, 5, accountId = "acc_1").code }
        assertEquals(listOf("000000", "000007", "999999", "123456"), codes)
        assertEquals(List(4) { 1_000_000 }, bounds, "exactly one bounded draw per code, bound = 10^6")
    }

    @Test
    fun `statistical sanity - the real generator fills the ten leading-digit buckets evenly`() {
        val buckets = IntArray(10)
        repeat(100_000) { buckets[open(withHandle = false, subject = "s${it % 7}").code[0] - '0']++ }
        assertTrue(buckets.all { it in 9_200..10_800 }, "expected ~10000 per bucket (8-sigma band), got ${buckets.toList()}")
    }

    @Test
    fun `the code is six ASCII digits whatever the JVM's default locale (Arabic-Indic and Devanagari digits would never match the 6-digit pattern)`() {
        val old = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-SA-u-nu-arab", "hi-IN-u-nu-deva", "fa-IR")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                val first = open().also { assertTrue(it.code.matches(Regex("^[0-9]{6}$")), "$tag: ${it.code}") }
                val reissued = challenges.reissue(first.row.id, ttl, 5, Duration.ZERO, 3)
                assertTrue(reissued == null || reissued.matches(Regex("^[0-9]{6}$")), "$tag reissue: $reissued")
            }
        } finally { java.util.Locale.setDefault(old) }
    }

    @Test
    fun `another session's guesses are refused BEFORE an attempt is spent - they cannot exhaust the owner's code`() {
        val o = open(ChallengePurposes.EMAIL_CHANGE, "acc_1", withHandle = false, sessionId = "ses_owner")
        repeat(8) { assertEquals(CodeCheck.Gone, challenges.check(o.row.id, "000000", "ses_other")) }
        assertEquals(5, store.find(o.row.id)!!.attemptsLeft, "no attempt was spent by the wrong session")
        assertTrue(challenges.check(o.row.id, o.code, "ses_owner") is CodeCheck.Ok)
    }

    @Test
    fun `only a keyed hash of the code is stored, never the code or the handle`() {
        val o = open()
        val row = store.find(o.row.id)!!
        assertNotEquals(o.code, row.codeHash)
        assertTrue(o.code !in row.codeHash && row.codeHash.length == 64)
        assertNotEquals(o.handle, row.id)
        assertEquals(row.id, challenges.idOf(o.handle!!))
        assertNotEquals(CodeHasher(key).hash(row.id, o.code), CodeHasher(ByteArray(32) { 9 }).hash(row.id, o.code), "the hash depends on the server secret")
        assertNotEquals(CodeHasher(key).hash("other-id", o.code), row.codeHash, "and on the challenge id")
    }

    @Test
    fun `the right code is accepted, a wrong one counts down, and attempts run out`() {
        val o = open()
        val id = o.row.id
        val wrong = if (o.code == "000000") "000001" else "000000"
        assertEquals(CodeCheck.Wrong(4), challenges.check(id, wrong))
        assertEquals(CodeCheck.Wrong(3), challenges.check(id, wrong))
        assertEquals(CodeCheck.Wrong(2), challenges.check(id, wrong))
        assertEquals(CodeCheck.Wrong(1), challenges.check(id, wrong))
        assertEquals(CodeCheck.Wrong(0), challenges.check(id, wrong))
        assertEquals(CodeCheck.Gone, challenges.check(id, o.code), "after five guesses even the right code is dead")
    }

    @Test
    fun `the right code returns the row and is single use`() {
        val o = open()
        val ok = challenges.check(o.row.id, o.code) as CodeCheck.Ok
        assertEquals(o.row.id, ok.row.id)
        assertTrue(challenges.consume(o.row.id))
        assertEquals(false, challenges.consume(o.row.id))
        assertEquals(CodeCheck.Gone, challenges.check(o.row.id, o.code))
    }

    @Test
    fun `an unknown id or an expired challenge is Gone`() {
        assertEquals(CodeCheck.Gone, challenges.check("nope", "123456"))
        val o = open()
        time.advance(ttl.plusSeconds(1))
        assertEquals(CodeCheck.Gone, challenges.check(o.row.id, o.code))
        assertNull(challenges.idOf("short"))
    }

    @Test
    fun `concurrent guesses can never spend more than the allowed attempts`() {
        val o = open()
        val wrong = if (o.code == "000000") "000001" else "000000"
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..64).map { pool.submit<CodeCheck> { go.await(); challenges.check(o.row.id, wrong) } }
        go.countDown()
        val seen = results.map { it.get() }
        pool.shutdown()
        assertEquals(5, seen.count { it is CodeCheck.Wrong }, "exactly the five allowed guesses were evaluated")
        assertEquals(59, seen.count { it == CodeCheck.Gone })
    }

    @Test
    fun `a session code is bound to its session, found by purpose and owner, and a new one replaces the old`() {
        val first = open(ChallengePurposes.REAUTH, "acc_1", withHandle = false, sessionId = "ses_a")
        assertNull(first.handle)
        assertEquals(CodeCheck.Gone, challenges.checkOpen(ChallengePurposes.REAUTH, "acc_1", first.code, "ses_other"), "another session cannot use it")
        val second = open(ChallengePurposes.REAUTH, "acc_1", withHandle = false, sessionId = "ses_a")
        assertEquals(second.row.id, challenges.findOpen(ChallengePurposes.REAUTH, "acc_1")!!.id)
        assertEquals(CodeCheck.Gone, challenges.check(first.row.id, first.code), "the replaced challenge is gone")
        assertTrue(challenges.checkOpen(ChallengePurposes.REAUTH, "acc_1", second.code, "ses_a") is CodeCheck.Ok)
    }

    @Test
    fun `a new code for the same attempt needs the cooldown, is capped, and resets the attempts`() {
        val o = open()
        val id = o.row.id
        repeat(3) { challenges.check(id, if (o.code == "000000") "000001" else "000000") }
        assertNull(challenges.reissue(id, ttl, 5, Duration.ofSeconds(30), 3), "inside the cooldown")
        time.advance(Duration.ofSeconds(31))
        val c2 = assertNotNull(challenges.reissue(id, ttl, 5, Duration.ofSeconds(30), 3))
        assertEquals(5, challenges.find(id)!!.attemptsLeft)
        assertTrue(o.code == c2 || challenges.check(id, o.code) is CodeCheck.Wrong, "the replaced code no longer works")
        time.advance(Duration.ofSeconds(31)); assertNotNull(challenges.reissue(id, ttl, 5, Duration.ofSeconds(30), 3))
        time.advance(Duration.ofSeconds(31)); assertNotNull(challenges.reissue(id, ttl, 5, Duration.ofSeconds(30), 3))
        time.advance(Duration.ofSeconds(31)); assertNull(challenges.reissue(id, ttl, 5, Duration.ofSeconds(30), 3), "at most three resends")
    }

    @Test
    fun `deleting by subject, by account and sweeping expired rows`() {
        open(ChallengePurposes.SIGN_UP, "a@example.com"); open(ChallengePurposes.SIGN_UP, "a@example.com"); open(ChallengePurposes.SIGN_UP, "b@example.com")
        assertEquals(2, challenges.deleteBySubject(ChallengePurposes.SIGN_UP, "a@example.com"))
        val r = challenges.open(ChallengePurposes.EMAIL_CHANGE, "acc_1", ttl, 5, accountId = "acc_1")
        challenges.open(ChallengePurposes.DELETE_CONFIRM, "acc_1", ttl, 5, accountId = "acc_1")
        assertEquals(2, challenges.deleteForAccount("acc_1"))
        assertNull(challenges.find(r.row.id))
        time.advance(Duration.ofDays(3))
        assertEquals(1, challenges.sweep(Duration.ofDays(1)))
    }
}
