package dev.sumin.skeleton.payment.kakaopay

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.payment-kakaopay")
data class KakaoPayProperties(
    val enabled: Boolean = false,
    val providerId: String = "kakaopay",
    val clientName: String = "payment-kakaopay",
    /** 개발자센터 앱의 Secret key. 테스트는 「Secret key(dev)」 + 가맹점 코드 `TC0ONETIME`. 비밀이라 [toString] 에 찍지 않는다. */
    val secretKey: String = "",
    /** 가맹점 코드(CID, 10자). 테스트는 `TC0ONETIME`. 비밀은 아니지만 `secret-key` 와 짝이다(프로젝트마다 자기 것). */
    val cid: String = "",
    /** 가맹점 코드 인증키(선택, 24자). 발급받았을 때만. 비밀이라 [toString] 에 찍지 않는다. */
    val cidSecret: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val readyPath: String = "/online/v1/payment/ready",
    val approvePath: String = "/online/v1/payment/approve",
    val cancelPath: String = "/online/v1/payment/cancel",
    val orderPath: String = "/online/v1/payment/order",
    val supportedCurrencies: Set<String> = setOf("KRW"),
    val supportedCountries: Set<String> = setOf("KR"),
    /**
     * 결제를 마친(또는 그만둔) 구매자를 돌려보낼 우리 화면의 주소(예: `https://app.example.com/payments/return`). 카카오페이는 `ready` 때 성공 · 취소 · 실패 주소를 서버가 받는다 -
     * 구매자가 정하는 값이 아니라 설정이다(열린 리다이렉트를 만들지 않는다). 모듈이 `provider` · `order` · `result` 조각을 붙이고 성공일 때 카카오페이가 `pg_token` 을 붙인다. 프로젝트마다 자기 것.
     */
    val returnUrl: String = "",
    /** `ready` · 조회 한 번의 제한 시간(요청 단위). 클라이언트 단위 `skeleton.http.clients.payment-kakaopay.response-timeout` 보다 짧을 때만 뜻이 있다. */
    val readyTimeout: Duration = Duration.ofSeconds(10),
    val lookupTimeout: Duration = Duration.ofSeconds(10),
    /** 보호 환경(stage · prod)에서 테스트 가맹점 코드로 서는 것을 일부러 허락한다(사업자 제휴 전의 시연 배포). */
    val allowTestKeysInProtectedEnv: Boolean = false,
) {
    /** 비밀 둘을 찍지 않는다(`TossPaymentProperties` 와 같은 방식) - 설정을 로그 · 예외 메시지에 찍어도 새지 않게. 주소는 값 대신 기본값인지만 말한다. */
    override fun toString(): String =
        "KakaoPayProperties(enabled=$enabled, providerId=$providerId, clientName=$clientName, secretKey=${redacted(secretKey)}, cid=$cid, cidSecret=${redacted(cidSecret)}, " +
            "baseUrl=${if (baseUrl == DEFAULT_BASE_URL) "<default>" else "<set>"}, supportedCurrencies=$supportedCurrencies, supportedCountries=$supportedCountries, " +
            "returnUrl=${if (returnUrl.isBlank()) "<unset>" else "<set>"}, readyTimeout=$readyTimeout, lookupTimeout=$lookupTimeout, allowTestKeysInProtectedEnv=$allowTestKeysInProtectedEnv)"

    private fun redacted(value: String) = if (value.isBlank()) "<unset>" else "<redacted>"
}

internal const val DEFAULT_BASE_URL = "https://open-api.kakaopay.com"
