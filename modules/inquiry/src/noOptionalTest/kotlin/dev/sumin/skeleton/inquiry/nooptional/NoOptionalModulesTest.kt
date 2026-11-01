package dev.sumin.skeleton.inquiry.nooptional

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.inquiry.InquiryAutoConfiguration
import dev.sumin.skeleton.inquiry.InquiryHumanCheck
import dev.sumin.skeleton.inquiry.InquiryListener
import dev.sumin.skeleton.inquiry.InquiryService
import dev.sumin.skeleton.inquiry.alert.InquiryAlertAutoConfiguration
import dev.sumin.skeleton.inquiry.captcha.InquiryTurnstileAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** captcha-turnstile · alert 가 클래스패스에 없어도(compileOnly) 모듈은 기동한다 — 연결 자동설정은 조용히 빠진다 */
class NoOptionalModulesTest {
    @Test
    fun `boots without captcha-turnstile and alert on the classpath`() {
        assertTrue(runCatching { Class.forName("dev.sumin.skeleton.alert.OwnerAlerts") }.isFailure)
        assertTrue(runCatching { Class.forName("dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier") }.isFailure)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InquiryAutoConfiguration::class.java, InquiryTurnstileAutoConfiguration::class.java, InquiryAlertAutoConfiguration::class.java))
            .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
            .run { ctx ->
                assertEquals(null, ctx.startupFailure)
                ctx.getBean(InquiryService::class.java)
                assertTrue(ctx.getBean(InquiryHumanCheck::class.java).passes(null, null))
                assertTrue(ctx.getBeansOfType(InquiryListener::class.java).isEmpty())
            }
    }
}
