package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.json.JacksonJsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxQuery
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class NotificationInboxErasureDbTest {
    private val dataSource = DbTestDatabase.dataSource().also {
        Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate()
    }
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val repository = JdbcNotificationInboxRepository(jdbc, JacksonJsonCodec(), DbTestDatabase.dialect)

    private fun event(recipients: Set<String>) = NotificationEvent(
        id = UUID.randomUUID().toString(), topic = "demo", type = "created", recipientIds = recipients, title = "Hello", message = "m",
        payload = mapOf("k" to "v"), createdAt = Instant.parse("2026-03-01T00:30:00.123456Z"),
    )

    @Test
    fun `erasing an account deletes its inbox and scrubs its id from other recipients' rows`() {
        val gone = "acc_gone_${UUID.randomUUID()}"
        val stay = "acc_stay_${UUID.randomUUID()}"
        val shared = event(setOf(gone, stay))
        repository.save(shared, setOf(gone, stay))
        val solo = event(setOf(gone))
        repository.save(solo, setOf(gone))

        NotificationInboxErasureListener(jdbc).erase(ErasureRequest(gone, "deleted:0123456789abcdef"))

        assertEquals(0, repository.findByRecipient(gone, NotificationInboxQuery(page = 0, size = 10)).totalElements)
        val remaining = repository.findByRecipient(stay, NotificationInboxQuery(page = 0, size = 10))
        assertEquals(1, remaining.totalElements)
        assertTrue(gone !in remaining.values.single().event.recipientIds, "the erased account's id must not stay in other people's rows")
        assertTrue("deleted:0123456789abcdef" in remaining.values.single().event.recipientIds)
        NotificationInboxErasureListener(jdbc).erase(ErasureRequest(gone, "deleted:0123456789abcdef"))   // idempotent
    }

    @Test
    fun `erasing acc_user leaves acc_user2 alone - only the whole quoted id is replaced`() {
        val base = "acc_${UUID.randomUUID().toString().take(8)}"
        val gone = base
        val longer = base + "2"          // the erased id is a prefix of this recipient's id
        val shared = event(setOf(gone, longer))
        repository.save(shared, setOf(gone, longer))

        NotificationInboxErasureListener(jdbc).erase(ErasureRequest(gone, "deleted:0123456789abcdef"))

        val remaining = repository.findByRecipient(longer, NotificationInboxQuery(page = 0, size = 10)).values.single().event.recipientIds
        assertTrue(longer in remaining, "the other recipient's id was mangled by a substring replace: $remaining")
        assertTrue("deleted:0123456789abcdef" in remaining && gone !in remaining)
    }
}
