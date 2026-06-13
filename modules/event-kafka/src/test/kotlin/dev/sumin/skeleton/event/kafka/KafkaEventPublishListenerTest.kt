package dev.sumin.skeleton.event.kafka

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class KafkaEventPublishListenerTest {
    private val properties = EventKafkaProperties(environment = "qa")
    private val sender = RecordingKafkaEventSender()
    private val listener = KafkaEventPublishListener(
        sender = sender,
        messageFactory = KafkaEventMessageFactory(
            properties = properties,
            partitionKeyStrategy = ConfiguredKafkaEventPartitionKeyStrategy(properties),
        ),
    )

    @AfterEach
    fun clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `publishes immediately when transaction synchronization is inactive`() {
        listener.onEvent(
            KafkaEvent(
                topic = "payments",
                type = "payment.authorized",
                id = "event-1",
                payload = mapOf("paymentId" to "payment-1"),
            ),
        )

        assertThat(sender.sent).hasSize(1)
        val message = sender.sent.single()
        assertThat(message.topic).isEqualTo("qa.payments")
        assertThat(message.headers).containsEntry(KafkaEventHeaders.EVENT_ID, "event-1")
    }

    @Test
    fun `defers publish until after commit when transaction synchronization is active`() {
        TransactionSynchronizationManager.initSynchronization()

        listener.onEvent(
            KafkaEvent(
                topic = "payments",
                type = "payment.captured",
                id = "event-2",
                payload = mapOf("paymentId" to "payment-2"),
            ),
        )

        assertThat(sender.sent).isEmpty()

        TransactionSynchronizationManager.getSynchronizations().forEach { synchronization ->
            synchronization.afterCommit()
        }

        assertThat(sender.sent).hasSize(1)
        val message = sender.sent.single()
        assertThat(message.topic).isEqualTo("qa.payments")
        assertThat(message.headers).containsEntry(KafkaEventHeaders.EVENT_ID, "event-2")
    }

    @Test
    fun `does not publish when synchronized transaction rolls back`() {
        TransactionSynchronizationManager.initSynchronization()

        listener.onEvent(
            KafkaEvent(
                topic = "payments",
                type = "payment.refund.failed",
                id = "event-rollback-1",
                payload = mapOf("paymentId" to "payment-rollback-1"),
            ),
        )

        assertThat(sender.sent).isEmpty()

        TransactionSynchronizationManager.getSynchronizations().forEach { synchronization ->
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        }

        assertThat(sender.sent).isEmpty()
    }

    private class RecordingKafkaEventSender : KafkaEventSender {
        val sent = mutableListOf<KafkaEventMessage>()

        override fun send(message: KafkaEventMessage) {
            sent += message
        }
    }
}
