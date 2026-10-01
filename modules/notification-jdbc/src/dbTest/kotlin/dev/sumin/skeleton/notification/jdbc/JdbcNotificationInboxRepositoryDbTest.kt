package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JacksonJsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxQuery
import java.time.Instant
import java.util.UUID
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
}
