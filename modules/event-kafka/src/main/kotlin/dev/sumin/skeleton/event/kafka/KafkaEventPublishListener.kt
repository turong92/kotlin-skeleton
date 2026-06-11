package dev.sumin.skeleton.event.kafka

import org.springframework.context.event.EventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class KafkaEventPublishListener(
    private val sender: KafkaEventSender,
    private val messageFactory: KafkaEventMessageFactory,
) {
    @EventListener
    fun onEvent(event: KafkaEvent) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        publish(event)
                    }
                },
            )
        } else {
            publish(event)
        }
    }

    private fun publish(event: KafkaEvent) {
        sender.send(messageFactory.toMessage(event))
    }
}
