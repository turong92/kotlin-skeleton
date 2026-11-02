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

    /** 지금은 줄마다 받는 사람 한 명만 적지만, 옛 줄에는 받는 사람 전체가 들어 있다 - 지우기가 그 줄도 닦는지 보려고 옛 모양으로 되돌린다 */
    private fun asLegacyRow(event: NotificationEvent, recipient: String) {
        val all = event.recipientIds.joinToString(",", "[", "]") { "\"$it\"" }
        jdbc.update("update notification_inbox set recipient_ids_json = :j where recipient_id = :r and event_id = :e", mapOf("j" to all, "r" to recipient, "e" to event.id))
    }

    @Test
    fun `erasing an account deletes its inbox and scrubs its id from other recipients' rows`() {
        val gone = "acc_gone_${UUID.randomUUID()}"
        val stay = "acc_stay_${UUID.randomUUID()}"
        val shared = event(setOf(gone, stay))
        repository.save(shared, setOf(gone, stay))
        asLegacyRow(shared, stay)
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
    fun `erasing an account deletes its mute rows and leaves other accounts' alone`() {
        val gone = "acc_gone_${UUID.randomUUID()}"
        val stay = "acc_stay_${UUID.randomUUID()}"
        val mutes = JdbcNotificationMuteRepository(jdbc, DbTestDatabase.dialect)
        mutes.setMuted(gone, "NOTE_UPDATED", true)
        mutes.setMuted(stay, "NOTE_UPDATED", true)

        NotificationInboxErasureListener(jdbc).erase(ErasureRequest(gone, "deleted:0123456789abcdef"))

        assertEquals(emptySet(), mutes.mutedTypes(gone))
        assertEquals(setOf("NOTE_UPDATED"), mutes.mutedTypes(stay))
    }

    @Test
    fun `erasing acc_user leaves acc_user2 alone - only the whole quoted id is replaced`() {
        val base = "acc_${UUID.randomUUID().toString().take(8)}"
        val gone = base
        val longer = base + "2"          // the erased id is a prefix of this recipient's id
        val shared = event(setOf(gone, longer))
        repository.save(shared, setOf(gone, longer))
        asLegacyRow(shared, longer)

        NotificationInboxErasureListener(jdbc).erase(ErasureRequest(gone, "deleted:0123456789abcdef"))

        val remaining = repository.findByRecipient(longer, NotificationInboxQuery(page = 0, size = 10)).values.single().event.recipientIds
        assertTrue(longer in remaining, "the other recipient's id was mangled by a substring replace: $remaining")
        assertTrue("deleted:0123456789abcdef" in remaining && gone !in remaining)
    }
}
