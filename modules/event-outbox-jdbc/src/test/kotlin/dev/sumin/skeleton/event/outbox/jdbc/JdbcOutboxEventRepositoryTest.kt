package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.KafkaEvent
import dev.sumin.skeleton.json.JacksonJsonCodec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class JdbcOutboxEventRepositoryTest {
    @Test
    fun `appends kafka event and finds it as due pending row`() {
        val dataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()
        JdbcTemplate(dataSource).execute(outboxSchemaSql())
        val repository = JdbcOutboxEventRepository(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            jsonCodec = JacksonJsonCodec(),
        )
        val event = KafkaEvent(
            topic = "payments",
            type = "payment.approved",
            id = "event-1",
            payload = mapOf("paymentId" to "payment-1"),
            partitionKey = "payment-1",
            headers = mapOf("source" to "test"),
            createdAt = Instant.parse("2026-06-22T00:00:00Z"),
        )

        val saved = repository.append(event)
        val due = repository.findDue(Instant.now().plusSeconds(60), 10)

        assertThat(saved.eventId).isEqualTo("event-1")
        assertThat(saved.status).isEqualTo(OutboxEventStatus.PENDING)
        assertThat(due).hasSize(1)
        assertThat(due.single().event.id).isEqualTo("event-1")
        assertThat(due.single().event.payload).isEqualTo(mapOf("paymentId" to "payment-1"))
        assertThat(due.single().event.partitionKey).isEqualTo("payment-1")
        assertThat(due.single().event.headers).containsEntry("source", "test")
    }

    @Test
    fun `marks due event as published or failed with next retry time`() {
        val dataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()
        JdbcTemplate(dataSource).execute(outboxSchemaSql())
        val repository = JdbcOutboxEventRepository(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            jsonCodec = JacksonJsonCodec(),
        )
        val saved = repository.append(
            KafkaEvent(
                topic = "payments",
                type = "payment.failed",
                id = "event-2",
                payload = mapOf("paymentId" to "payment-2"),
                createdAt = Instant.parse("2026-06-22T00:00:00Z"),
            ),
        )
        val failedAt = Instant.parse("2026-06-22T00:00:10Z")
        val retryAt = Instant.parse("2026-06-22T00:01:10Z")

        repository.markFailed(saved.id, failedAt, retryAt, "kafka down")
        val retryable = repository.findDue(retryAt, 10).single()
        repository.markPublished(saved.id, Instant.parse("2026-06-22T00:01:11Z"))
        val afterPublished = repository.findDue(Instant.parse("2026-06-22T00:02:00Z"), 10)

        assertThat(retryable.status).isEqualTo(OutboxEventStatus.FAILED)
        assertThat(retryable.attempts).isEqualTo(1)
        assertThat(retryable.lastError).isEqualTo("kafka down")
        assertThat(afterPublished).isEmpty()
    }

    private fun outboxSchemaSql(): String =
        """
        create table skeleton_event_outbox (
            id varchar(128) not null,
            event_id varchar(128) not null,
            topic varchar(255) not null,
            type varchar(255) not null,
            partition_key varchar(255),
            payload_json clob not null,
            headers_json clob not null,
            status varchar(32) not null,
            attempts integer not null,
            available_at timestamp not null,
            event_created_at timestamp not null,
            published_at timestamp,
            failed_at timestamp,
            last_error varchar(2000),
            created_at timestamp not null,
            updated_at timestamp not null,
            primary key (id),
            unique (event_id)
        )
        """.trimIndent()
}
