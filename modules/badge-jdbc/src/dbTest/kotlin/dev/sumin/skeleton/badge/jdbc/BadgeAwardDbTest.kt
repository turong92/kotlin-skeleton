package dev.sumin.skeleton.badge.jdbc

import dev.sumin.skeleton.badge.BadgeAction
import dev.sumin.skeleton.badge.BadgeAwardStatus
import dev.sumin.skeleton.badge.BadgeAwards
import dev.sumin.skeleton.badge.BadgeCatalog
import dev.sumin.skeleton.badge.BadgeDefinition
import dev.sumin.skeleton.badge.BadgeDefinitionInput
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.badge.BadgeErrorCode
import dev.sumin.skeleton.badge.BadgeException
import dev.sumin.skeleton.badge.BadgeSource
import dev.sumin.skeleton.badge.BadgeStore
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 동시 수여 한 줄 · `(AWARDED, GRANT, 지급 건 id)` 유니크 · 회수 뒤 재수여 · 커서 · 공개 질의가 두 방언(postgresTest · mysqlTest)에서 지켜지는지 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [BadgeJdbcTestApplication::class])
class BadgeAwardDbTest {
    @Autowired lateinit var definitions: BadgeDefinitions
    @Autowired lateinit var awards: BadgeAwards
    @Autowired lateinit var catalog: BadgeCatalog
    @Autowired lateinit var store: BadgeStore
    @Autowired lateinit var issuer: RewardIssuer
    @Autowired lateinit var jdbc: JdbcClient

    private val seq = UUID.randomUUID().toString().take(8)

    private fun define(code: String, hidden: Boolean = false, sortOrder: Int = 0): BadgeDefinition =
        definitions.create(BadgeDefinitionInput("$code-$seq", "Badge $code", "about", null, hidden, sortOrder))

    private fun account(n: Int) = "acc_${seq}_$n"

    private fun count(table: String, where: String, vararg args: Any) =
        jdbc.sql("select count(*) from $table where $where").params(*args).query(Long::class.java).single()

    private fun <T> concurrently(count: Int, block: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { i -> pool.submit<T> { start.await(); block(i) } }
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun `concurrent awards of one badge to one account leave one award line and one event`() {
        val badge = define("race")
        val sameRef = concurrently(8) { awards.award(account(1), badge.code, "note:1") }
        assertEquals(1, sameRef.count { it.newlyAwarded })
        assertEquals(1, sameRef.map { it.awardId }.toSet().size)

        val other = define("race2")
        val differentRefs = concurrently(8) { awards.award(account(1), other.code, "note:$it") }
        assertEquals(1, differentRefs.count { it.newlyAwarded })

        assertEquals(1, count("badge_awards", "badge_id = ? and account_id = ?", badge.id, account(1)))
        assertEquals(1, count("badge_award_events", "badge_id = ? and action = 'AWARDED'", badge.id))
        assertEquals(1, count("badge_award_events", "badge_id = ? and action = 'AWARDED'", other.id))
    }

    @Test
    fun `concurrent operator awards over the same accounts give each account one line`() {
        val badge = define("bulk")
        val accounts = (1..6).map { account(it) }
        val results = concurrently(4) { awards.awardByOperator(badge.id, accounts, "launch", "op_1") }
        assertEquals(accounts.size, results.sumOf { it.awarded.size })
        assertEquals(accounts.size.toLong(), count("badge_awards", "badge_id = ?", badge.id))
        assertEquals(accounts.size.toLong(), count("badge_award_events", "badge_id = ? and action = 'AWARDED'", badge.id))
    }

    @Test
    fun `the same grant twice is one award and one event, even after a revoke`() {
        val badge = define("grant")
        val view = RewardGrantView("rgr_$seq", account(2), "badge", mapOf("code" to badge.code), "CAMPAIGN", "k")
        val first = issuer.issue(view)
        val second = issuer.issue(view)
        assertEquals(first.resultRef, second.resultRef)
        awards.revoke(account(2), badge.code, "test")
        assertEquals(first.resultRef, issuer.issue(view).resultRef)
        assertEquals(BadgeAwardStatus.REVOKED, store.lockAwardById(first.resultRef)!!.status)
        assertEquals(1, count("badge_award_events", "source_type = 'GRANT' and source_ref = ?", view.id))
        assertEquals(1, count("badge_award_events", "badge_id = ? and action = 'AWARDED'", badge.id))
    }

    @Test
    fun `the event unique key folds a repeated source and lets rows without a source ref repeat`() {
        val badge = define("events")
        val holding = awards.award(account(3), badge.code, "r")
        val event = store.pageEvents(badge.id, null, null, 10).single()
        assertFalse(store.insertEventIfAbsent(event.copy(id = "bde_dup_$seq")))
        assertTrue(store.insertEventIfAbsent(event.copy(id = "bde_nullref1_$seq", action = BadgeAction.REVOKED, source = BadgeSource.ADMIN, sourceRef = null)))
        assertTrue(store.insertEventIfAbsent(event.copy(id = "bde_nullref2_$seq", action = BadgeAction.REVOKED, source = BadgeSource.ADMIN, sourceRef = null)))
        assertEquals(holding.awardId, event.awardId)
    }

    @Test
    fun `a revoked badge comes back on the same line with a new event`() {
        val badge = define("again")
        val first = awards.award(account(4), badge.code, "r1")
        assertTrue(awards.revoke(account(4), badge.code, "mistake"))
        assertFalse(awards.revoke(account(4), badge.code, "mistake"))
        val back = awards.award(account(4), badge.code, "r2")
        assertTrue(back.newlyAwarded)
        assertEquals(first.awardId, back.awardId)
        assertEquals(1, count("badge_awards", "badge_id = ?", badge.id))
        assertEquals(listOf(BadgeAction.AWARDED, BadgeAction.REVOKED, BadgeAction.AWARDED), store.pageEvents(badge.id, null, null, 10).reversed().map { it.action })
    }

    @Test
    fun `a taken code is refused and counting holders skips revoked lines`() {
        val badge = define("dup")
        assertEquals(BadgeErrorCode.CODE_TAKEN, assertFailsWith<BadgeException> { definitions.create(BadgeDefinitionInput(badge.code, "x", "", null, false, 0)) }.badgeError)
        awards.award(account(5), badge.code, "r")
        awards.award(account(6), badge.code, "r")
        awards.revoke(account(6), badge.code, "x")
        assertEquals(1, definitions.list().first { it.badge.id == badge.id }.holders)
    }

    @Test
    fun `history pages newest first by cursor`() {
        val badge = define("page")
        (1..5).forEach { awards.award(account(10 + it), badge.code, "r") }
        val seen = ArrayList<String>()
        var cursor: String? = null
        do {
            val page = awards.history(badge.id, null, cursor, 2)
            seen += page.items.map { it.event.id }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(5, seen.size)
        assertEquals(seen.sortedDescending(), seen)
        assertEquals(5, seen.toSet().size)
    }

    @Test
    fun `the public query reads only active and visible lines, in sort order`() {
        val b = define("pub-b", sortOrder = 2)
        val a = define("pub-a", sortOrder = 1)
        val c = define("pub-c", sortOrder = 3)
        val gone = define("pub-gone", sortOrder = 4)
        val secret = define("pub-secret", hidden = true, sortOrder = 5)
        val me = account(20)
        awards.award(me, b.code, "r")
        awards.award(me, a.code, "r")
        val tucked = awards.award(me, c.code, "r")
        awards.award(me, gone.code, "r")
        awards.revoke(me, gone.code, "x")
        awards.award(me, secret.code, "r")   // 받은 숨은 업적은 안 숨겼다면 보인다
        catalog.setVisible(me, tucked.awardId, false)

        val shown = awards.holdingsOf(listOf(me, account(21)))
        assertEquals(listOf(a.code, b.code, secret.code), shown.getValue(me).map { it.code })
        assertEquals(emptyList(), shown.getValue(account(21)))
        assertEquals(emptyList(), catalog.publicOf("acc_never_existed_$seq"))
        assertEquals(setOf("code", "name", "description", "imageUrl"), shown.getValue(me).first().let { it::class.java.declaredFields.filterNot { f -> f.isSynthetic }.map { f -> f.name }.toSet() })
    }

    @Test
    fun `setting visibility works only on the owner's active line`() {
        val badge = define("vis")
        val held = awards.award(account(30), badge.code, "r")
        assertFailsWith<BadgeException> { catalog.setVisible(account(31), held.awardId, false) }
        catalog.setVisible(account(30), held.awardId, false)
        assertEquals(emptyList(), catalog.publicOf(account(30)))
        awards.revoke(account(30), badge.code, "x")
        assertFailsWith<BadgeException> { catalog.setVisible(account(30), held.awardId, true) }
    }

    @Test
    fun `erasing an account removes its lines and events, repeatably`() {
        val badge = define("erase")
        awards.award(account(40), badge.code, "r")
        awards.award(account(41), badge.code, "r")
        store.eraseAccount(account(40))
        store.eraseAccount(account(40))
        assertEquals(0, count("badge_awards", "account_id = ?", account(40)))
        assertEquals(0, count("badge_award_events", "account_id = ?", account(40)))
        assertEquals(1, count("badge_awards", "account_id = ?", account(41)))
        assertNotEquals(0L, count("badge_award_events", "account_id = ?", account(41)))
    }
}
