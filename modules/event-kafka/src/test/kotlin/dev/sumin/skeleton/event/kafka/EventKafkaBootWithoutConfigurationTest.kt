package dev.sumin.skeleton.event.kafka

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 모듈만 얹고 설정이 없으면 뜬다 — Kafka 없이 로깅 전송기로 (`skeleton.event-kafka.enabled=true` 로 켠다). */
class EventKafkaBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration and no Kafka`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventKafkaAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(LoggingKafkaEventSender::class.java)
            }
    }
}
