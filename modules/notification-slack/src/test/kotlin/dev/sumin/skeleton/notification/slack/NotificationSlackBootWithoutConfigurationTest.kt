package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.logging.RedactionAutoConfiguration
import dev.sumin.skeleton.common.observability.ObservabilityLinkAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 모듈 + 선언된 의존(notification, platform)만 얹고 설정이 없으면 뜬다 — 웹훅 URL 없이. */
class NotificationSlackBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RedactionAutoConfiguration::class.java,
                    OutboundHttpAutoConfiguration::class.java,
                    ObservabilityLinkAutoConfiguration::class.java,
                    NotificationAutoConfiguration::class.java,
                    NotificationSlackAutoConfiguration::class.java,
                ),
            )
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
