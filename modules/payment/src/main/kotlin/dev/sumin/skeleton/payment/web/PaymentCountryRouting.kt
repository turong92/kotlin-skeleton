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
 * 화면이 보낸 `provider` 는 읽지 않는다 - 서버 값만 쓴다.
 *
 * @param openable 결제창을 열 수 있는 결제사 id - 부를 때마다 읽는다
 */
class PaymentCountryRouting(
    private val properties: PaymentProperties,
    private val clientIps: ClientIps,
    private val openable: () -> List<String>,
) {
    fun of(request: HttpServletRequest): RequestRoute {
        val devSwitch = properties.routing.devCountrySwitch
        val country = (if (devSwitch) devHeader(request) else null) ?: properties.routing.devCountry ?: clientIps.of(request).country ?: properties.subscription.cancel.defaultCountry
        val open = openable()
        val mapped = country?.let { properties.routing.countryProviders[it] }?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val provider = if (mapped != null) mapped.takeIf { it in open } else defaultProvider()?.takeIf { it in open } ?: open.singleOrNull()
        return RequestRoute(country, provider, policyFor(country), providerClosed = mapped != null && provider == null, devSwitch = devSwitch)
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
