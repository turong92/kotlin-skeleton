package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/** 모듈 + 선언된 의존(notification)만 얹고 설정이 없으면 뜬다 — 외부 브로커 없이 인프로세스 심플 브로커로. */
class NotificationWebSocketBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration in a servlet application`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, NotificationWebSocketAutoConfiguration::class.java))
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
