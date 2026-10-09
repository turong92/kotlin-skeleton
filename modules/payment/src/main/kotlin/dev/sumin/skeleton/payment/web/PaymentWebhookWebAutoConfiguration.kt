package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.logging.LogMaskingContributor
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 웹훅 수신 경로(`<skeleton.payment.http.base-path>/webhooks/…`) — 서블릿 웹 앱이고 **장부의 수신함([PaymentWebhookInbox] 빈)이 있을 때만**, `skeleton.payment.webhook.enabled=false` 로 끈다.
 * 경로는 인증 없이 열려야 하므로 [PublicEndpointContributor] 로 연다(`auth` 의 기본 체인이 이 목록을 읽어 `permitAll` 을 건다) — POST 두 모양뿐이다.
 */
@AutoConfiguration(after = [PaymentLedgerAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(PaymentWebhookInbox::class)
@ConditionalOnProperty(prefix = "skeleton.payment.webhook", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PaymentWebhookWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun paymentWebhookRateLimiter(stores: ObjectProvider<RateLimitStore>, time: ObjectProvider<TimeProvider>, properties: PaymentProperties): PaymentWebhookRateLimiter {
        val fallback by lazy { InMemoryFixedWindowRateLimitStore() }
        return PaymentWebhookRateLimiter({ stores.getIfAvailable { fallback } }, time.getIfAvailable { TimeProvider.systemUtc() }, properties.webhook.rateLimit)
    }

    @Bean
    @ConditionalOnMissingBean
    fun paymentWebhookController(
        inbox: PaymentWebhookInbox,
        limiter: PaymentWebhookRateLimiter,
        properties: PaymentProperties,
        clientIps: ObjectProvider<ClientIps>,
    ): PaymentWebhookController = PaymentWebhookController(inbox, limiter, properties, clientIps.getIfAvailable { ClientIps() })

    /**
     * 주소의 비밀 조각을 우리 로그에서 가린다 — `<base-path>/webhooks/<provider>/` 뒤의 것 전부(세 조각짜리 잘못된 주소의 나머지까지). 요청 로그 줄은 이 규칙을 직접 거치고,
     * 앱이 logback 가림 조각을 쓰면 오류 로그 줄 · Spring 의 메시지에도 같은 규칙이 닿는다. `base-path` 가 바뀌면 규칙도 따라간다.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentWebhookLogMasking"])
    fun paymentWebhookLogMasking(properties: PaymentProperties): LogMaskingContributor =
        LogMaskingContributor { listOf(webhookPathSecretPattern(properties.http.basePath)) }

    @Bean
    @ConditionalOnMissingBean(name = ["paymentWebhookPublicEndpointContributor"])
    fun paymentWebhookPublicEndpointContributor(properties: PaymentProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("POST", "${properties.http.basePath}/webhooks/*")
            registry.add("POST", "${properties.http.basePath}/webhooks/*/*")
        }
}

/**
 * `<base-path>/webhooks/<provider>/<여기부터 끝까지>` 의 `value` 그룹 — **공백 · 줄 끝 앞까지**(`&` · 따옴표가 든 토큰의 뒷부분이 찍히지 않게). 두 번째 조각 이후는 슬래시를 넘어 전부 가린다.
 * 요청 로그의 경로는 가림막이 512자로 자른 뒤에 이 규칙을 돌린다. 앞에 고정 글자(`<base-path>/webhooks/`)가 있고 반복은 한 번씩 훑는 두 개뿐이라 입력 길이에 선형이다.
 */
internal fun webhookPathSecretPattern(basePath: String): String =
    """${Regex.escape(basePath)}/webhooks/[^/\s]+/(?<value>\S+)"""
