package dev.sumin.skeleton.payment.paddle

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.payment-paddle` — Paddle Billing(v2 API). 세 비밀([apiKey] · [clientToken] · [webhookSecret])이 모두 있어야 제공자 빈이 선다.
 * 비밀은 [toString] 에 찍지 않는다.
 */
@ConfigurationProperties("skeleton.payment-paddle")
data class PaddleProperties(
    val enabled: Boolean = false,
    val providerId: String = "paddle",
    val clientName: String = "payment-paddle",
    /** 서버가 Paddle API 를 부르는 키(구독 해지). */
    val apiKey: String = "",
    /** Paddle.js 가 쓰는 클라이언트 토큰(공개해도 된다). */
    val clientToken: String = "",
    /** 웹훅 엔드포인트의 비밀값(`pdl_ntfset_…`) — 서명 검증. */
    val webhookSecret: String = "",
    /** `sandbox` | `live`. */
    val environment: String = "sandbox",
    /** 비우면 환경에 따른 기본 주소. 시험의 가짜 Paddle 은 여기에 적는다. */
    val baseUrl: String = "",
    /** 서명의 시각이 지금과 이보다 멀면 거절한다(재전송 방지). Paddle SDK 의 기본은 5초다 — 시계 오차를 감안해 넉넉히. */
    val signatureTolerance: Duration = Duration.ofSeconds(60),
    /** 거래 만들기(요청 한 번)의 제한 시간 - 주문 · 구독 시작이 이 호출을 기다린다. */
    val createTransactionTimeout: Duration = Duration.ofSeconds(5),
    /** 플랜 코드(구독) 또는 상품 코드(단건) → Paddle 가격 id(`pri_…`). 금액은 Paddle 가격이 정한다. */
    val prices: Map<String, String> = emptyMap(),
    val supportedCurrencies: Set<String> = emptySet(),
    val supportedCountries: Set<String> = emptySet(),
) {
    val sandbox: Boolean get() = !environment.trim().equals("live", ignoreCase = true)

    val resolvedBaseUrl: String
        get() = baseUrl.ifBlank { if (sandbox) "https://sandbox-api.paddle.com" else "https://api.paddle.com" }

    override fun toString(): String =
        "PaddlePaymentProperties(enabled=$enabled, providerId=$providerId, environment=$environment, apiKey=${mark(apiKey)}, clientToken=${mark(clientToken)}, webhookSecret=${mark(webhookSecret)}, prices=${prices.keys})"

    private fun mark(value: String) = if (value.isBlank()) "<unset>" else "<redacted>"
}
