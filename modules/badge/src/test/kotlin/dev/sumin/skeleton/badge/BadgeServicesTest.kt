package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.account.AccountFacts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BadgeServicesTest {
    private val store = FakeBadgeStore()
    private val time = MutableTime()
    private val notifier = RecordingNotifier()
    private val definitions = BadgeDefinitions(store, time)
    private val awards = BadgeAwards(store, time) { notifier }
    private val catalog = BadgeCatalog(store)

    private fun define(code: String, hidden: Boolean = false, sortOrder: Int = 0) =
        definitions.create(BadgeDefinitionInput(code, "Badge $code", "about $code", null, hidden, sortOrder))

    @BeforeTest
    fun reset() {
        store.clear()
        notifier.sent.clear()
    }

    @Test
    fun `award twice with the same ref gives one award and one event, notified once`() {
        define("first-note")
        val first = awards.award("acc_1", "first-note", "note:1")
        val second = awards.award("acc_1", "first-note", "note:1")
        assertTrue(first.newlyAwarded)
        assertFalse(second.newlyAwarded)
        assertTrue(first.active && second.active)
        assertEquals(first.awardId, second.awardId)
        assertEquals(1, store.awards.size)
        assertEquals(1, store.events.size)
        assertEquals(listOf(Triple("acc_1", "first-note", BadgeSource.APP)), notifier.sent)
    }

    @Test
    fun `the same ref is not awarded again after a revoke, a new ref is`() {
        define("first-note")
        val first = awards.award("acc_1", "first-note", "note:1")
        assertTrue(awards.revoke("acc_1", "first-note", "mistake"))
        val replay = awards.award("acc_1", "first-note", "note:1")
        assertFalse(replay.newlyAwarded)
        assertFalse(replay.active, "a replay after a revoke must not look like a holding")
        assertEquals(BadgeAwardStatus.REVOKED, store.awards.getValue(first.awardId).status)
        val again = awards.award("acc_1", "first-note", "note:2")
        assertTrue(again.newlyAwarded)
        assertEquals(first.awardId, again.awardId)
        assertEquals(BadgeAwardStatus.ACTIVE, store.awards.getValue(first.awardId).status)
    }

    @Test
    fun `the same ref for another account or another badge is its own award`() {
        define("a")
        define("b")
        assertTrue(awards.award("acc_1", "a", "r").newlyAwarded)
        assertTrue(awards.award("acc_2", "a", "r").newlyAwarded)
        assertTrue(awards.award("acc_1", "b", "r").newlyAwarded)
        assertEquals(3, store.events.size)
    }

    @Test
    fun `an unknown badge is 404 and an archived one is 409`() {
        define("old")
        definitions.setStatus(store.findDefinitionByCode("old")!!.id, BadgeStatus.ARCHIVED)
        assertEquals(BadgeErrorCode.NOT_FOUND, assertFailsWith<BadgeException> { awards.award("acc_1", "nope", "r") }.badgeError)
        assertEquals(BadgeErrorCode.ARCHIVED, assertFailsWith<BadgeException> { awards.award("acc_1", "old", "r") }.badgeError)
        assertEquals(0, store.awards.size)
    }

    @Test
    fun `concurrent awards of one badge to one account leave one award and one event`() {
        define("first-note")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (0 until 8).map { pool.submit<Boolean> { start.await(); awards.award("acc_1", "first-note", "note:1").newlyAwarded } }
        start.countDown()
        val created = futures.map { it.get() }.count { it }
        pool.shutdown()
        assertEquals(1, created)
        assertEquals(1, store.awards.size)
        assertEquals(1, store.events.size)
    }

    @Test
    fun `a grant is folded by its id even after a revoke, and does not notify (reward does)`() {
        define("first-note")
        val view = grant()
        val first = awards.awardForGrant(view.accountId, "first-note", view.id)
        awards.revoke("acc_1", "first-note", "no")
        val retried = awards.awardForGrant(view.accountId, "first-note", view.id)
        assertTrue(first.newlyAwarded)
        assertFalse(retried.newlyAwarded)
        assertEquals(BadgeAwardStatus.REVOKED, store.awards.getValue(first.awardId).status)
        assertEquals(0, notifier.sent.size)
        assertEquals(listOf(BadgeSource.GRANT, BadgeSource.APP), store.events.map { it.source })
    }

    @Test
    fun `re-awarding a held badge with a new ref or by an operator sends no second notification`() {
        val badge = define("first-note")
        awards.award("acc_1", "first-note", "note:1")
        awards.award("acc_1", "first-note", "note:2")
        awards.awardByOperator(badge.id, listOf("acc_1"), null, "op_1")
        assertEquals(1, notifier.sent.size)
    }

    @Test
    fun `an operator award reports who was newly given and who already held it`() {
        val badge = define("early-bird")
        awards.award("acc_2", "early-bird", "seed")
        val result = awards.awardByOperator(badge.id, listOf("acc_1", "acc_2", "acc_1"), "launch", "op_1")
        assertEquals(listOf("acc_1"), result.awarded)
        assertEquals(listOf("acc_2"), result.alreadyHeld)
        val event = store.events.last()
        assertEquals(BadgeSource.ADMIN, event.source)
        assertEquals("op_1", event.actorId)
        assertEquals("launch", event.reason)
        assertEquals(listOf("acc_2", "acc_1").sorted(), notifier.sent.map { it.first }.sorted())
    }

    @Test
    fun `an operator award skips accounts that do not exist and counts them apart`() {
        val badge = define("early-bird")
        val known = setOf("acc_1")
        val checked = BadgeAwards(store, time, notifier = { notifier }, accountFacts = { AccountFacts { if (it in known) time.now() else null } })
        val result = checked.awardByOperator(badge.id, listOf("acc_1", "ghost"), null, "op_1")
        assertEquals(listOf("acc_1"), result.awarded)
        assertEquals(listOf("ghost"), result.unknownAccountIds)
        assertEquals(1, store.awards.size)
        assertEquals(listOf("acc_1"), notifier.sent.map { it.first })
    }

    @Test
    fun `an operator award takes the accounts in sorted order whatever the input order`() {
        val badge = define("early-bird")
        awards.awardByOperator(badge.id, listOf("acc_3", "acc_1", "acc_2"), null, "op_1")
        assertEquals(listOf("acc_1", "acc_2", "acc_3"), store.events.map { it.accountId })
    }

    @Test
    fun `an operator award is limited to 100 accounts`() {
        val badge = define("early-bird")
        val tooMany = (1..101).map { "acc_$it" }
        assertEquals(BadgeErrorCode.CONFIG_INVALID, assertFailsWith<BadgeException> { awards.awardByOperator(badge.id, tooMany, null, "op_1") }.badgeError)
    }

    @Test
    fun `revoking needs a reason, records it, and a second revoke is a 404`() {
        val badge = define("early-bird")
        val held = awards.award("acc_1", "early-bird", "seed")
        assertFailsWith<BadgeException> { awards.revokeByOperator(held.awardId, "  ", "op_1") }
        awards.revokeByOperator(held.awardId, "abuse", "op_1")
        val event = store.events.last()
        assertEquals(BadgeAction.REVOKED, event.action)
        assertEquals("abuse", event.reason)
        assertEquals("op_1", event.actorId)
        assertEquals(BadgeErrorCode.NOT_FOUND, assertFailsWith<BadgeException> { awards.revokeByOperator(held.awardId, "abuse", "op_1") }.badgeError)
        assertEquals(0, definitions.list().first { it.badge.id == badge.id }.holders)
    }

    @Test
    fun `holdingsOf and the public view carry only active and visible badges, in sort order`() {
        define("b", sortOrder = 2)
        define("a", sortOrder = 1)
        define("c", sortOrder = 3)
        awards.award("acc_1", "b", "r")
        awards.award("acc_1", "a", "r")
        val hidden = awards.award("acc_1", "c", "r")
        catalog.setVisible("acc_1", hidden.awardId, false)
        awards.award("acc_2", "a", "r")
        awards.revoke("acc_2", "a", "x")
        val holdings = awards.holdingsOf(listOf("acc_1", "acc_2", "acc_3"))
        assertEquals(listOf("a", "b"), holdings.getValue("acc_1").map { it.code })
        assertEquals(emptyList(), holdings.getValue("acc_2"))
        assertEquals(emptyList(), holdings.getValue("acc_3"))
    }

    @Test
    fun `my badges hide a locked hidden definition and show an archived one only when earned`() {
        define("shown")
        define("secret", hidden = true)
        define("retired")
        define("retired-earned")
        awards.award("acc_1", "retired-earned", "r")
        listOf("retired", "retired-earned").forEach { definitions.setStatus(store.findDefinitionByCode(it)!!.id, BadgeStatus.ARCHIVED) }
        val mine = catalog.mine("acc_1")
        assertEquals(listOf("retired-earned"), mine.earned.map { it.definition.code })
        assertEquals(setOf("shown", "secret"), mine.locked.map { it.code }.toSet())
    }

    @Test
    fun `only the owner flips visibility`() {
        define("a")
        val held = awards.award("acc_1", "a", "r")
        assertEquals(BadgeErrorCode.NOT_FOUND, assertFailsWith<BadgeException> { catalog.setVisible("acc_2", held.awardId, false) }.badgeError)
        assertTrue(store.awards.getValue(held.awardId).visible)
        catalog.setVisible("acc_1", held.awardId, false)
        assertFalse(store.awards.getValue(held.awardId).visible)
    }

    @Test
    fun `definitions validate the code and the picture address, and a code is taken once`() {
        fun invalid(input: BadgeDefinitionInput) = assertFailsWith<BadgeException> { definitions.create(input) }.badgeError
        assertEquals(BadgeErrorCode.CONFIG_INVALID, invalid(BadgeDefinitionInput("Bad Code", "n", "d", null, false, 0)))
        assertEquals(BadgeErrorCode.CONFIG_INVALID, invalid(BadgeDefinitionInput("", "n", "d", null, false, 0)))
        assertEquals(BadgeErrorCode.CONFIG_INVALID, invalid(BadgeDefinitionInput("x".repeat(65), "n", "d", null, false, 0)))
        assertEquals(BadgeErrorCode.CONFIG_INVALID, invalid(BadgeDefinitionInput("ok", " ", "d", null, false, 0)))
        assertEquals(BadgeErrorCode.CONFIG_INVALID, invalid(BadgeDefinitionInput("ok", "n", "d", "javascript:alert(1)", false, 0)))
        assertEquals(BadgeErrorCode.CONFIG_INVALID, invalid(BadgeDefinitionInput("ok", "n", "d", "//evil.example.com/x.png", false, 0)))
        assertEquals("https://cdn.example.com/a.png", definitions.create(BadgeDefinitionInput("ok", "n", "d", "https://cdn.example.com/a.png", false, 0)).imageUrl)
        assertEquals("/img/b.png", definitions.create(BadgeDefinitionInput("ok-2", "n", "d", "/img/b.png", false, 0)).imageUrl)
        assertEquals(BadgeErrorCode.CODE_TAKEN, invalid(BadgeDefinitionInput("ok", "again", "d", null, false, 0)))
    }

    @Test
    fun `an update changes everything but the code, and an unknown id is 404`() {
        val created = define("keep-me")
        val updated = definitions.update(created.id, BadgeDefinitionInput("ignored", "New name", "New text", "/i.png", true, 7))
        assertEquals("keep-me", updated.code)
        assertEquals("New name", updated.name)
        assertTrue(updated.hidden)
        assertEquals(7, updated.sortOrder)
        assertEquals(BadgeErrorCode.NOT_FOUND, assertFailsWith<BadgeException> { definitions.update("bdg_none", BadgeDefinitionInput("x", "n", "d", null, false, 0)) }.badgeError)
        assertNull(definitions.find("bdg_none"))
    }

    @Test
    fun `history pages newest first and filters`() {
        val a = define("a")
        define("b")
        awards.award("acc_1", "a", "r1")
        awards.award("acc_2", "a", "r2")
        awards.award("acc_1", "b", "r3")
        val first = awards.history(null, null, null, 2)
        assertEquals(2, first.items.size)
        assertEquals(store.events.last().id, first.items.first().event.id)
        val second = awards.history(null, null, first.nextCursor, 2)
        assertEquals(1, second.items.size)
        assertNull(second.nextCursor)
        assertEquals(2, awards.history(a.id, null, null, 10).items.size)
        assertEquals(2, awards.history(null, "acc_1", null, 10).items.size)
    }

    @Test
    fun `erasing an account removes its awards and events and is repeatable`() {
        define("a")
        awards.award("acc_1", "a", "r")
        store.eraseAccount("acc_1")
        store.eraseAccount("acc_1")
        assertEquals(0, store.awards.size)
        assertEquals(0, store.events.size)
    }
}
