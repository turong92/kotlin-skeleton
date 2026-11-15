package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.captcha.turnstile.TurnstileProperties
import dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.inquiry.alert.InquiryAlertAutoConfiguration
import dev.sumin.skeleton.inquiry.captcha.InquiryTurnstileAutoConfiguration
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** alert · captcha-turnstile 이 있을 때만 잇는다 — 본문 · 이메일은 경보에 싣지 않는다 */
class InquiryOptionalIntegrationsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InquiryAutoConfiguration::class.java, InquiryTurnstileAutoConfiguration::class.java, InquiryAlertAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    class Alerts : OwnerAlerts {
        val emitted = mutableListOf<Triple<AlertKind, String, String>>()
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { emitted += Triple(kind, key, detail) }
    }

    @Configuration(proxyBeanMethods = false)
    class FakeAlerts {
        @Bean fun alerts(): OwnerAlerts = Alerts()
    }

    @Configuration(proxyBeanMethods = false)
    class FakeTurnstile {
        @Bean fun verifier(): TurnstileVerifier {
            val http = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalHttpClient::class.java)) { _, _, _ -> error("no network in this test") } as ExternalHttpClient
            return TurnstileVerifier(http, TurnstileProperties(enabled = true, secretKey = "x"))
        }
    }

    @Test
    fun `with owner alerts a received inquiry raises one alert that carries the id and kind but no body or email`() {
        runner.withUserConfiguration(FakeAlerts::class.java).run { ctx ->
            val service = ctx.getBean(InquiryService::class.java)
            val saved = service.submit(InquiryRequest(kind = "BUG", body = "비밀 본문이 들어 있어요 열 글자", replyEmail = "me@example.com", captchaToken = "t"), null, "ip", null)
            val (kind, _, detail) = (ctx.getBean(OwnerAlerts::class.java) as Alerts).emitted.single()
            assertEquals("INQUIRY_RECEIVED", kind.name)
            assertTrue(detail.contains("#${saved.id}"))
            assertTrue(detail.contains("BUG"))
            assertFalse(detail.contains("비밀 본문") || detail.contains("me@example.com"))
        }
        runner.run { ctx -> assertTrue(ctx.getBeansOfType(InquiryListener::class.java).isEmpty()) }
    }

    @Test
    fun `alerts share one key and fold within the configured interval - a flood is not one alert per inquiry`() {
        runner.withUserConfiguration(FakeAlerts::class.java).run { ctx ->
            val service = ctx.getBean(InquiryService::class.java)
            repeat(2) { service.submit(InquiryRequest(kind = "BUG", body = "도배 시험 문의 열 글자 이상", captchaToken = "t"), null, "ip$it", null) }
            val emitted = (ctx.getBean(OwnerAlerts::class.java) as Alerts).emitted
            assertEquals(1, emitted.map { it.second }.distinct().size)
            assertEquals(java.time.Duration.ofMinutes(10), emitted.first().first.minInterval)
        }
    }

    @Test
    fun `a turnstile verifier becomes the human check, and a missing token fails without any network call`() {
        runner.withUserConfiguration(FakeTurnstile::class.java).run { ctx ->
            assertFalse(ctx.getBean(InquiryHumanCheck::class.java).passes(null, "203.0.113.1"))
        }
        runner.run { ctx -> assertTrue(ctx.getBean(InquiryHumanCheck::class.java).passes(null, null)) }
    }

    @Test
    fun `an app's own human check wins over the turnstile bridge`() {
        runner.withUserConfiguration(FakeTurnstile::class.java).withBean(InquiryHumanCheck::class.java, { InquiryHumanCheck { _, _ -> true } })
            .run { ctx -> assertTrue(ctx.getBean(InquiryHumanCheck::class.java).passes(null, null)) }
    }

}
