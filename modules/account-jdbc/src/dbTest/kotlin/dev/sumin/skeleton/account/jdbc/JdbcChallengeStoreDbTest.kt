package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.ChallengeRow
import dev.sumin.skeleton.account.challenge.Challenges
import dev.sumin.skeleton.account.challenge.CodeCheck
import dev.sumin.skeleton.account.challenge.CodeHasher
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 코드 챌린지 저장소의 원자성 — 두 DB(PostgreSQL · MySQL)에서 같게: 동시 추측은 시도 수를 넘기지 못하고, 이긴 소비는 하나다 */
class JdbcChallengeStoreDbTest {
    private val now = Instant.parse("2026-10-06T00:00:00.654321Z")
    private val challenges = Challenges(AccountDb.challenges, CodeHasher(ByteArray(32) { 1 }), TimeProvider.fixed(now))
    private val ttl = Duration.ofMinutes(10)

    @BeforeTest fun clean() = AccountDb.clean()

    @Test
    fun `a row round-trips with microsecond times, a password hash and a payload, and only the hash of the code is stored`() {
        val o = challenges.open(ChallengePurposes.SIGN_UP, "ann@example.com", ttl, 5, payload = """{"displayName":"Ann"}""", secret = "{bcrypt}x", ip = "203.0.113.1", withHandle = true)
        val row: ChallengeRow = AccountDb.challenges.find(o.row.id)!!
        assertEquals(o.row, row)
        assertEquals(now.plus(ttl), row.expiresAt)
        assertEquals(0, AccountDb.jdbc.queryForObject("select count(*) from account_challenges where code_hash = :c or id = :c", mapOf("c" to o.code), Int::class.java))
    }

    @Test
    fun `a consumed attempt can be reopened with the spent guess given back - the nickname clash at verification does not burn the attempt`() {
        val o = challenges.open(ChallengePurposes.SIGN_UP, "ann@example.com", ttl, 5, payload = """{"displayName":"Ann"}""", secret = "{bcrypt}x", withHandle = true)
        val ok = challenges.check(o.row.id, o.code) as CodeCheck.Ok
        assertEquals(4, ok.row.attemptsLeft)
        assertTrue(challenges.consume(o.row.id))
        assertNull(AccountDb.challenges.find(o.row.id))
        challenges.reopen(ok.row)
        val back = AccountDb.challenges.find(o.row.id)!!
        assertEquals(5, back.attemptsLeft, "the right code's guess was given back")
        assertEquals(o.row.expiresAt, back.expiresAt); assertEquals("{bcrypt}x", back.secret); assertEquals("""{"displayName":"Ann"}""", back.payload)
        assertTrue(challenges.check(o.row.id, o.code) is CodeCheck.Ok, "the same code works again")
    }

    @Test
    fun `sixty-four concurrent wrong guesses spend exactly the five allowed attempts`() {
        val o = challenges.open(ChallengePurposes.SIGN_UP, "ann@example.com", ttl, 5, withHandle = true)
        val wrong = if (o.code == "000000") "000001" else "000000"
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..64).map { pool.submit<CodeCheck> { go.await(); challenges.check(o.row.id, wrong) } }
        go.countDown()
        val seen = results.map { it.get() }
        pool.shutdown()
        assertEquals(5, seen.count { it is CodeCheck.Wrong })
        assertEquals(CodeCheck.Gone, challenges.check(o.row.id, o.code), "after the attempts are spent even the right code is dead")
    }

    @Test
    fun `consuming a challenge is won by exactly one of sixteen racers`() {
        val o = challenges.open(ChallengePurposes.SIGN_UP, "ann@example.com", ttl, 5, withHandle = true)
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit<Boolean> { go.await(); challenges.consume(o.row.id) } }
        go.countDown()
        assertEquals(1, results.count { it.get() })
        pool.shutdown()
    }

    @Test
    fun `an unspent window, expiry, a new code, session codes replacing each other, deletes and the sweep`() {
        val a = challenges.open(ChallengePurposes.SIGN_UP, "ann@example.com", ttl, 5, withHandle = true)
        val b = challenges.open(ChallengePurposes.SIGN_UP, "ann@example.com", ttl, 5, withHandle = true)
        assertNotNull(AccountDb.challenges.find(a.row.id)); assertNotNull(AccountDb.challenges.find(b.row.id))   // attempts coexist

        val late = Challenges(AccountDb.challenges, CodeHasher(ByteArray(32) { 1 }), TimeProvider.fixed(now.plus(ttl).plusSeconds(1)))
        assertEquals(CodeCheck.Gone, late.check(a.row.id, a.code), "expired")
        assertNull(late.findOpen(ChallengePurposes.SIGN_UP, "ann@example.com"))

        val fresh = Challenges(AccountDb.challenges, CodeHasher(ByteArray(32) { 1 }), TimeProvider.fixed(now.plusSeconds(60)))
        val newCode = assertNotNull(fresh.reissue(a.row.id, ttl, 5, Duration.ofSeconds(30), 3))
        assertNull(fresh.reissue(a.row.id, ttl, 5, Duration.ofSeconds(30), 3), "inside the cooldown of the new send")
        assertTrue(fresh.check(a.row.id, newCode) is CodeCheck.Ok)

        val s1 = challenges.open(ChallengePurposes.REAUTH, "acc_1", ttl, 5, accountId = "acc_1", sessionId = "ses_1")
        val s2 = challenges.open(ChallengePurposes.REAUTH, "acc_1", ttl, 5, accountId = "acc_1", sessionId = "ses_1")
        assertNull(AccountDb.challenges.find(s1.row.id), "a new session code replaces the open one")
        assertEquals(s2.row.id, challenges.findOpen(ChallengePurposes.REAUTH, "acc_1")!!.id)
        assertEquals(CodeCheck.Gone, challenges.check(s2.row.id, s2.code, "ses_other"))

        challenges.open(ChallengePurposes.DELETE_CONFIRM, "acc_1", ttl, 5, accountId = "acc_1")
        assertEquals(2, challenges.deleteForAccount("acc_1"))
        assertEquals(2, challenges.deleteBySubject(ChallengePurposes.SIGN_UP, "ann@example.com"))
        challenges.open(ChallengePurposes.SIGN_UP, "x@example.com", ttl, 5, withHandle = true)
        assertEquals(1, Challenges(AccountDb.challenges, CodeHasher(ByteArray(32) { 1 }), TimeProvider.fixed(now.plus(Duration.ofDays(3)))).sweep(Duration.ofDays(1)))
    }

    @Test
    fun `addresses that differ only by accent or case never touch each other's attempts`() {
        challenges.open(ChallengePurposes.SIGN_UP, "victim@gmail.com", ttl, 5, withHandle = true)
        assertEquals(0, challenges.deleteBySubject(ChallengePurposes.SIGN_UP, "victim@gmäil.com"))
        assertEquals(0, challenges.deleteBySubject(ChallengePurposes.SIGN_UP, "Victim@gmail.com"))
        assertEquals(1, challenges.deleteBySubject(ChallengePurposes.SIGN_UP, "victim@gmail.com"))
    }
}
