package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JacksonJsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxQuery
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 모듈 마이그레이션(db/migration/<vendor>) 을 Flyway 로 깔고 실제 DB 에서 저장 · 조회 · 읽음 · 중복 저장을 검증. */
class JdbcNotificationInboxRepositoryDbTest {
    private val dataSource = DbTestDatabase.dataSource().also {
        Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate()
    }
    private val repository = JdbcNotificationInboxRepository(NamedParameterJdbcTemplate(dataSource), JacksonJsonCodec(), DbTestDatabase.dialect)

    private fun event(id: String = UUID.randomUUID().toString()) = NotificationEvent(
        id = id,
        topic = "demo",
        type = "created",
        recipientIds = setOf("acc_user"),
        title = "Hello",
        message = "Stored notification",
        payload = mapOf("source" to "test", "count" to 1),
        createdAt = Instant.parse("2026-03-01T00:30:00.123456Z"),
    )

    @Test
    fun `persists notifications and read state`() {
        val e = event()
        val recipient = "acc_${UUID.randomUUID()}"
        repository.save(e, setOf(recipient, "acc_other"))
        val readAt = Instant.parse("2026-03-01T00:31:00Z")
        val read = repository.markRead(recipient, e.id, readAt)
        val unread = repository.findByRecipient(recipient, NotificationInboxQuery(page = 0, size = 10, unreadOnly = true))
        val all = repository.findByRecipient(recipient, NotificationInboxQuery(page = 0, size = 10))

        assertEquals(1, all.totalElements)
        assertEquals(e.createdAt, all.values.single().event.createdAt)
        assertEquals("test", all.values.single().event.payload["source"])
        assertNotNull(read)
        assertEquals(readAt, read.readAt)
        assertEquals(0, unread.totalElements)
    }

    @Test
    fun `saving the same event twice inside one transaction keeps the transaction usable`() {
        val e = event()
        val recipient = "acc_${UUID.randomUUID()}"
        val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val count = tx.execute {
            repository.save(e, setOf(recipient))
            repository.save(e, setOf(recipient)) // PG: 예외 삼키기였다면 여기서 트랜잭션 abort → 다음 쿼리 실패
            repository.findByRecipient(recipient, NotificationInboxQuery(page = 0, size = 10)).totalElements
        }
        assertEquals(1, count)
    }

    private fun collapsing(id: String, key: String, topic: String = "demo", at: String = "2026-03-01T00:30:00Z", title: String = id) = NotificationEvent(
        id = id,
        topic = topic,
        type = "created",
        title = title,
        link = "/n/$id",
        collapseKey = key,
        createdAt = Instant.parse(at),
    )

    @Test
    fun `an unread event with the same collapse key folds into one row with the newest content and the first event id`() {
        val recipient = "acc_${UUID.randomUUID()}"
        val first = repository.save(collapsing("e1-$recipient", "k"), setOf(recipient)).single()
        val folded = repository.save(collapsing("e2-$recipient", "k", at = "2026-03-01T00:35:00Z", title = "newest"), setOf(recipient)).single()

        val page = repository.findByRecipient(recipient, NotificationInboxQuery())
        val row = page.values.single()
        assertEquals(1, first.collapsedCount)
        assertEquals(row, folded)
        assertEquals("e1-$recipient", row.event.id)
        assertEquals("newest", row.event.title)
        assertEquals("/n/e2-$recipient", row.event.link)
        assertEquals(Instant.parse("2026-03-01T00:35:00Z"), row.event.createdAt)
        assertEquals(2, row.collapsedCount)
        assertEquals(1L, repository.countUnread(recipient))
    }

    @Test
    fun `publishing the same event id again keeps the count`() {
        val recipient = "acc_${UUID.randomUUID()}"
        val e = collapsing("e1-$recipient", "k")
        repository.save(e, setOf(recipient))
        repository.save(e, setOf(recipient))

        assertEquals(1, repository.findByRecipient(recipient, NotificationInboxQuery()).values.single().collapsedCount)
    }

    @Test
    fun `after the row is read the same collapse key starts a new row, and a read row is never folded into`() {
        val recipient = "acc_${UUID.randomUUID()}"
        repository.save(collapsing("e1-$recipient", "k"), setOf(recipient))
        repository.markRead(recipient, "e1-$recipient")
        repository.save(collapsing("e2-$recipient", "k", at = "2026-03-01T00:40:00Z"), setOf(recipient))
        repository.save(collapsing("e3-$recipient", "k", at = "2026-03-01T00:41:00Z"), setOf(recipient))

        val rows = repository.findByRecipient(recipient, NotificationInboxQuery()).values
        assertEquals(listOf("e2-$recipient", "e1-$recipient"), rows.map { it.event.id })
        assertEquals(listOf(2, 1), rows.map { it.collapsedCount })
        assertNotNull(rows.last().readAt)
        // markAllRead 도 열쇠를 놓는다
        repository.markAllRead(recipient)
        repository.save(collapsing("e4-$recipient", "k", at = "2026-03-01T00:42:00Z"), setOf(recipient))
        assertEquals(3, repository.findByRecipient(recipient, NotificationInboxQuery()).totalElements)
    }

    @Test
    fun `events without a collapse key never fold and other recipients fold on their own`() {
        val a = "acc_${UUID.randomUUID()}"
        val b = "acc_${UUID.randomUUID()}"
        repository.save(collapsing("p1-$a", "k"), setOf(a, b))
        repository.save(collapsing("p2-$a", "k"), setOf(a))
        repository.save(NotificationEvent(id = "n1-$a", topic = "demo", type = "t", createdAt = Instant.parse("2026-03-01T00:30:00Z")), setOf(a))
        repository.save(NotificationEvent(id = "n2-$a", topic = "demo", type = "t", createdAt = Instant.parse("2026-03-01T00:31:00Z")), setOf(a))

        assertEquals(3, repository.findByRecipient(a, NotificationInboxQuery()).totalElements)
        assertEquals(1, repository.findByRecipient(b, NotificationInboxQuery()).values.single().collapsedCount)
    }

    @Test
    fun `two transactions folding the same key at once end with one row of count 2`() {
        val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val recipient = "acc_${UUID.randomUUID()}"
                val start = CountDownLatch(1)
                val futures = (1..2).map { n ->
                    pool.submit {
                        start.await()
                        tx.execute {
                            repository.save(collapsing("e$n-$recipient", "k-$round"), setOf(recipient))
                            Thread.sleep(80)   // 먼저 넣은 쪽이 커밋하기 전에 다른 쪽이 접기를 시도하게 겹친다
                        }
                    }
                }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }

                val rows = repository.findByRecipient(recipient, NotificationInboxQuery()).values
                assertEquals(1, rows.size, "round $round")
                assertEquals(2, rows.single().collapsedCount, "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `countUnread counts unread rows of one recipient`() {
        val recipient = "acc_${UUID.randomUUID()}"
        repository.save(event("c1-$recipient"), setOf(recipient))
        repository.save(event("c2-$recipient"), setOf(recipient))
        repository.save(collapsing("c3-$recipient", "k"), setOf(recipient))
        repository.save(collapsing("c4-$recipient", "k"), setOf(recipient))
        assertEquals(3L, repository.countUnread(recipient))
        repository.markRead(recipient, "c1-$recipient")
        assertEquals(2L, repository.countUnread(recipient))
        assertEquals(0L, repository.countUnread("acc_${UUID.randomUUID()}"))
    }

    @Test
    fun `deleteOlderThan keeps rows at the cutoff and deletes in several batches`() {
        val recipient = "acc_${UUID.randomUUID()}"
        val topic = "retention-${UUID.randomUUID()}"
        val cutoff = Instant.parse("2026-02-01T00:00:00Z")
        repeat(2500) { i ->
            repository.save(NotificationEvent(id = "old$i-$recipient", topic = topic, type = "t", createdAt = Instant.parse("2026-01-01T00:00:00Z")), setOf(recipient))
        }
        repository.save(collapsing("edge-$recipient", "edge", topic = topic, at = "2026-02-01T00:00:00Z"), setOf(recipient))
        repository.save(collapsing("new-$recipient", "new", topic = topic, at = "2026-03-01T00:00:00Z"), setOf(recipient))
        repository.markRead(recipient, "old0-$recipient")   // 읽음 여부와 무관하게 지운다

        // 이 DB 의 다른 시험 줄은 모두 2026-03-01 이후라 cutoff 보다 옛 줄은 이 시험의 2500 줄뿐이다
        assertEquals(2500, repository.deleteOlderThan(cutoff))
        val left = repository.findByRecipient(recipient, NotificationInboxQuery(size = 100)).values.map { it.event.id }
        assertEquals(setOf("edge-$recipient", "new-$recipient"), left.toSet())
        assertEquals(0, repository.deleteOlderThan(cutoff))
    }

    @Test
    fun `deleteByTopic and deleteByRecipientAndTopic`() {
        val a = "acc_${UUID.randomUUID()}"
        val b = "acc_${UUID.randomUUID()}"
        val topic = "topic-${UUID.randomUUID()}"
        val other = "other-${UUID.randomUUID()}"
        repository.save(NotificationEvent(id = "t1-$a", topic = topic, type = "t"), setOf(a, b))
        repository.save(NotificationEvent(id = "t2-$a", topic = other, type = "t"), setOf(a, b))

        assertEquals(1, repository.deleteByRecipientAndTopic(a, topic))
        assertEquals(0, repository.deleteByRecipientAndTopic(a, topic))
        assertEquals(1, repository.findByRecipient(b, NotificationInboxQuery(topic = topic)).totalElements)
        assertEquals(1, repository.deleteByTopic(topic))
        assertEquals(setOf(other), repository.findByRecipient(a, NotificationInboxQuery()).values.map { it.event.topic }.toSet())
        assertEquals(0, repository.deleteByTopic(topic))
    }
}
