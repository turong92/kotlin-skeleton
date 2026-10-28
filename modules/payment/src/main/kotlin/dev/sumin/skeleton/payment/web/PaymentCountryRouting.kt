package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PeriodEndOnlyCancelPolicy
import dev.sumin.skeleton.payment.KoreaConsumerCancelPolicy
import jakarta.servlet.http.HttpServletRequest

/** 그 나라의 해지·환불 정책 요약 - [key] 는 정책 이름(`korea` · `period-end-only` …), 숫자는 한국 정책일 때만(설정값). */
data class CancelPolicySummary(val key: String, val penaltyPercent: Int?, val withdrawalDays: Long?)

/**
 * 이 요청의 접속 나라 · 서버가 정한 결제사(없으면 null) · 그 나라의 해지·환불 정책.
 * [providerClosed] 는 나라 지도가 결제사를 정했는데 그 결제사가 지금 결제창을 못 여는 상태다 - 다른 결제사로 넘기지 않는다(주문 · 구독 만들기는 `PAYMENT.UNAVAILABLE`).
 */
data class RequestRoute(val country: String?, val provider: String?, val cancelPolicy: CancelPolicySummary, val providerClosed: Boolean = false, val devSwitch: Boolean = false)

/**
 * 접속 나라로 결제사와 해지·환불 정책을 **서버가** 정한다(매 요청 - 세션에 박지 않는다).
 *
 * 나라: 요청별 개발용 헤더(`dev-country-switch` 가 켜졌을 때만) → 로컬 스위치(`dev-country`) (둘 다 보호 환경에서는 기동 실패) → Cloudflare 가 붙인 `CF-IPCountry`(클라이언트 IP 모드가 cloudflare 이고 믿는 프록시에서 온 것만,
 * [ClientIps]) → 설정의 기본 나라(`skeleton.payment.subscription.cancel.default-country`) → 없음.
 * 결제사: 나라 지도에 그 나라가 있으면 그 결제사(**못 열면 다른 결제사로 넘기지 않는다** - [RequestRoute.providerClosed]). 지도에 없는 나라는 기본 결제사 → 열린 결제사가 하나뿐이면 그것.
 * 화면이 보낸 `provider` 는 [chosen] 만 읽는다 - 이 나라를 `supportedCountries` 에 적은 열린 결제사([choices]) 안에서만 받고, 그 밖은 서버 값을 쓴다.
 *
 * @param openable 결제창을 열 수 있는 결제사 id - 부를 때마다 읽는다
 */
class PaymentCountryRouting(
    private val propertiesOf: () -> PaymentProperties,
    private val clientIps: ClientIps,
    private val openable: () -> List<String>,
    /** 이 나라를 **자기 `supportedCountries` 에 적은** 결제사 id - 같은 나라의 결제사가 둘 이상 열려 있을 때 구매자가 고를 수 있는 후보의 출처. 기본은 없음(고르기 없음). */
    private val countryProviders: (String) -> List<String> = { emptyList() },
    /** 빌링(카드 등록 · 간편결제 정기) 능력을 가진 결제사 id - [subscriptionChoices] 의 거름. 기본은 없음. */
    private val billingProviders: () -> List<String> = { emptyList() },
    /** 이 결제사가 **실효 설정으로** 받는 통화 - [currencyOf] 가 「받는 통화가 하나뿐」을 판정하는 출처. 기본은 모름. */
    private val currenciesOf: (String) -> Set<String> = { emptySet() },
) {
    constructor(properties: PaymentProperties, clientIps: ClientIps, openable: () -> List<String>) : this({ properties }, clientIps, openable)

    private val properties: PaymentProperties get() = propertiesOf()

    fun of(request: HttpServletRequest): RequestRoute {
        val devSwitch = properties.routing.devCountrySwitch
        val country = (if (devSwitch) devHeader(request) else null) ?: properties.routing.devCountry ?: clientIps.of(request).country ?: properties.subscription.cancel.defaultCountry
        val open = openable()
        val mapped = country?.let { properties.routing.countryProviders[it] }?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val provider = if (mapped != null) mapped.takeIf { it in open } else defaultProvider()?.takeIf { it in open } ?: open.singleOrNull()
        return RequestRoute(country, provider, policyFor(country), providerClosed = mapped != null && provider == null, devSwitch = devSwitch)
    }

    /**
     * 구매자가 고를 수 있는 결제사 - 이 요청의 나라를 자기 `supportedCountries` 에 적은, 지금 결제창을 열 수 있는 결제사(토스 + 카카오페이처럼 같은 나라에 둘 이상을 운영자가 켰을 때).
     * 나라 지도가 정한 결제사가 못 열리면(`providerClosed`) 비운다 - 조용히 다른 결제사로 넘기지 않는다는 규칙이 구매자의 선택으로도 깨지지 않는다.
     */
    fun choices(route: RequestRoute): List<String> {
        val country = route.country ?: return emptyList()
        if (route.providerClosed) return emptyList()
        val open = openable()
        return countryProviders(country).filter { it in open }.distinct()
    }

    /** 화면이 보낸 `provider` 를 [choices] 안에 있을 때만 받는다. 아니면 서버가 정한 값(그대로 null 일 수 있다). 다른 나라의 결제사를 고르는 길은 없다. */
    fun chosen(route: RequestRoute, requested: String?): String? {
        val wanted = requested?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return route.provider
        return if (wanted in choices(route)) wanted else route.provider
    }

    /** 구독을 시작할 때 구매자가 고를 수 있는 결제사 - [choices] 가운데 빌링 능력을 가진 것. */
    fun subscriptionChoices(route: RequestRoute): List<String> = choices(route).filter { it in billingProviders() }

    /** 화면이 보낸 `provider` 를 [subscriptionChoices] 안에 있을 때만 받는다. 아니면 서버가 정한 값. */
    fun chosenForSubscription(route: RequestRoute, requested: String?): String? {
        val wanted = requested?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return route.provider
        return if (wanted in subscriptionChoices(route)) wanted else route.provider
    }

    /**
     * 이 요청이 결제할 통화 - 라우팅이 고른 결제사의 통화다(결제 화면과 카탈로그가 같은 답을 쓴다). 순서: `routing.provider-currencies.<결제사>` → 그 결제사가 받는 통화가 **하나뿐**이면 그것 →
     * `routing.default-currency` → 없음. 결제사를 못 정했으면(`provider == null`) 곧바로 기본 통화.
     */
    fun currencyOf(route: RequestRoute): String? {
        val provider = route.provider
        if (provider != null) {
            properties.routing.providerCurrencies.entries.firstOrNull { it.key.trim().lowercase() == provider }?.let { return it.value }
            currenciesOf(provider).singleOrNull()?.let { return it }
        }
        return properties.routing.defaultCurrency
    }

    /** 개발용 스위치가 켜졌을 때만 읽는 요청별 나라 - 두 글자 영문이 아니면 없는 것으로 본다. */
    private fun devHeader(request: HttpServletRequest): String? =
        request.getHeader(DEV_COUNTRY_HEADER)?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }

    private fun defaultProvider(): String? = properties.defaultProvider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun policyFor(country: String?): CancelPolicySummary {
        val cancel = properties.subscription.cancel
        val key = country?.let { cancel.policies[it] } ?: PeriodEndOnlyCancelPolicy.name
        return if (key == KoreaConsumerCancelPolicy.NAME) CancelPolicySummary(key, cancel.korea.penaltyPercent, cancel.korea.withdrawalDays) else CancelPolicySummary(key, null, null)
    }

    companion object {
        const val DEV_COUNTRY_HEADER = "X-Dev-Country"
    }
}
