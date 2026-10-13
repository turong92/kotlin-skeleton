package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PeriodEndOnlyCancelPolicy
import dev.sumin.skeleton.payment.KoreaConsumerCancelPolicy
import jakarta.servlet.http.HttpServletRequest

/** 그 나라의 해지·환불 정책 요약 - [key] 는 정책 이름(`korea` · `period-end-only` …), 숫자는 한국 정책일 때만(설정값). */
data class CancelPolicySummary(val key: String, val penaltyPercent: Int?, val withdrawalDays: Long?)

/** 이 요청의 접속 나라 · 서버가 정한 결제사(없으면 null) · 그 나라의 해지·환불 정책. */
data class RequestRoute(val country: String?, val provider: String?, val cancelPolicy: CancelPolicySummary)

/**
 * 접속 나라로 결제사와 해지·환불 정책을 **서버가** 정한다(매 요청 - 세션에 박지 않는다).
 *
 * 나라: 로컬 스위치(`skeleton.payment.routing.dev-country`, 보호 환경에서는 기동 실패) → Cloudflare 가 붙인 `CF-IPCountry`(클라이언트 IP 모드가 cloudflare 이고 믿는 프록시에서 온 것만,
 * [ClientIps]) → 설정의 기본 나라(`skeleton.payment.subscription.cancel.default-country`) → 없음.
 * 결제사: 나라 지도 → 기본 결제사 → 열린 결제사가 하나뿐이면 그것. 열린 결제사만 고른다. 화면이 보낸 `provider` 는 [requireSame] 이 서버 값과 대조한다.
 *
 * @param openable 결제창을 열 수 있는 결제사 id - 부를 때마다 읽는다
 */
class PaymentCountryRouting(
    private val properties: PaymentProperties,
    private val clientIps: ClientIps,
    private val openable: () -> List<String>,
) {
    fun of(request: HttpServletRequest): RequestRoute {
        val country = properties.routing.devCountry ?: clientIps.of(request).country ?: properties.subscription.cancel.defaultCountry
        return RequestRoute(country, providerFor(country), policyFor(country))
    }

    /** 화면이 보낸 결제사가 서버가 정한 것과 다르면 거절한다(400) - 화면이 결제사를 바꾸지 못한다. 보내지 않았으면 서버 값이다. */
    fun requireSame(route: RequestRoute, requested: String?) {
        if (requested != null && requested != route.provider) {
            throw FieldValidationException("provider", "Pattern", "provider must be the one the server chose for this request's country")
        }
    }

    private fun providerFor(country: String?): String? {
        val open = openable()
        val wanted = (country?.let { properties.routing.countryProviders[it] } ?: properties.defaultProvider)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return wanted?.takeIf { it in open } ?: open.singleOrNull()
    }

    private fun policyFor(country: String?): CancelPolicySummary {
        val cancel = properties.subscription.cancel
        val key = country?.let { cancel.policies[it] } ?: PeriodEndOnlyCancelPolicy.name
        return if (key == KoreaConsumerCancelPolicy.NAME) CancelPolicySummary(key, cancel.korea.penaltyPercent, cancel.korea.withdrawalDays) else CancelPolicySummary(key, null, null)
    }
}
