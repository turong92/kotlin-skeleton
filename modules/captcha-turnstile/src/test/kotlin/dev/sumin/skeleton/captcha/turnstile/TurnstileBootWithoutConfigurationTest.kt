package dev.sumin.skeleton.captcha.turnstile

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 모듈 + 선언된 의존(platform)만 얹고 설정이 없으면 뜬다 — 시크릿 키 없이, 검증기는 `enabled=true` + secret-key 를 줘야 생긴다. */
class TurnstileBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, TurnstileAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(TurnstileVerifier::class.java)
            }
    }
}
