package dev.sumin.skeleton.event.kafka

import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class EventKafkaAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EventKafkaAutoConfiguration::class.java))

    @Test
    fun `disabled mode creates publisher and logging sender without Kafka operations`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(EventKafkaProperties::class.java)
            assertThat(context).hasSingleBean(KafkaEventPublisher::class.java)
            assertThat(context).hasSingleBean(KafkaEventPublishListener::class.java)
            assertThat(context).hasSingleBean(KafkaEventMessageFactory::class.java)
            assertThat(context).hasSingleBean(KafkaEventPartitionKeyStrategy::class.java)
            assertThat(context).hasSingleBean(LoggingKafkaEventSender::class.java)
        }
    }

    @Test
    fun `backs off when user provides sender`() {
        val sender = RecordingKafkaEventSender()

        contextRunner
            .withBean(KafkaEventSender::class.java, Supplier { sender })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(KafkaEventSender::class.java)).isSameAs(sender)
                assertThat(context).doesNotHaveBean(LoggingKafkaEventSender::class.java)
            }
    }

    @Test
    fun `publisher emits Spring application event consumed by publish listener`() {
        val sender = RecordingKafkaEventSender()

        contextRunner
            .withPropertyValues("skeleton.event-kafka.environment=test")
            .withBean(KafkaEventSender::class.java, Supplier { sender })
            .run { context ->
                context.getBean(KafkaEventPublisher::class.java).publish(
                    KafkaEvent(
                        topic = "accounts",
                        type = "account.created",
                        id = "event-1",
                        payload = mapOf("accountId" to "account-1"),
                        partitionKey = "account-1",
                    ),
                )

                assertThat(sender.sent).hasSize(1)
                val message = sender.sent.single()
                assertThat(message.topic).isEqualTo("test.accounts")
                assertThat(message.key).isEqualTo("account-1")
                assertThat(message.headers).containsEntry(KafkaEventHeaders.EVENT_ID, "event-1")
            }
    }

    private class RecordingKafkaEventSender : KafkaEventSender {
        val sent = mutableListOf<KafkaEventMessage>()

        override fun send(message: KafkaEventMessage) {
            sent += message
        }
    }
}
