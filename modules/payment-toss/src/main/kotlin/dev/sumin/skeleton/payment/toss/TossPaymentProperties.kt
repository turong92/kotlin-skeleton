package dev.sumin.skeleton.payment.toss

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.payment-toss")
data class TossPaymentProperties(
    val enabled: Boolean = false,
    val providerId: String = "toss",
    val clientName: String = "payment-toss",
    val secretKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val confirmPath: String = "/v1/payments/confirm",
    val cancelPath: String = "/v1/payments/{paymentKey}/cancel",
    val supportedCurrencies: Set<String> = setOf("KRW"),
    val supportedCountries: Set<String> = setOf("KR"),
    /** 결제창을 여는 공개 키(`test_ck_…`/`live_ck_…`). 서버가 `checkout` 응답으로 프론트에 준다 — 프론트 환경변수를 두지 않는다. */
    val clientKey: String = "",
    val lookupPath: String = "/v1/payments/{paymentKey}",
    val lookupByOrderPath: String = "/v1/payments/orders/{orderId}",
    /** 조회 한 번의 제한 시간(요청 단위). 클라이언트 단위 `skeleton.http.clients.payment-toss.response-timeout` 보다 짧을 때만 뜻이 있다. */
    val lookupTimeout: Duration = Duration.ofSeconds(10),
    /** 보호 환경(stage · prod)에서 테스트 키로 서는 것을 일부러 허락한다(사업자등록 전의 시연 배포). */
    val allowTestKeysInProtectedEnv: Boolean = false,
    /**
     * 결제창 안에서 바로 열 간편결제(`NAVERPAY` · `KAKAOPAY` · `TOSSPAY` …, [TossEasyPays.CODES]). 기본 빈 목록 = 카드 단추 하나, 지금과 같다.
     * checkout 응답에 실려 프론트가 단추를 하나씩 그린다. 상점 계약(MID)에 있는 것만 열린다. 모르는 코드는 기동 실패.
     */
    val easyPays: List<String> = emptyList(),
) {
    init {
        TossEasyPays.normalize(easyPays)
    }

    /** 시크릿 키를 찍지 않는다(`PaymentProperties.Webhook` 과 같은 방식) — 설정을 로그 · 예외 메시지에 찍어도 새지 않게. 주소(`baseUrl`)도 값 대신 기본값인지만 말한다(가드가 주소 값을 일부러 안 적는 것과 같다). */
    override fun toString(): String =
        "TossPaymentProperties(enabled=$enabled, providerId=$providerId, clientName=$clientName, secretKey=${if (secretKey.isBlank()) "<unset>" else "<redacted>"}, " +
            "baseUrl=${if (baseUrl == DEFAULT_BASE_URL) "<default>" else "<set>"}, confirmPath=$confirmPath, cancelPath=$cancelPath, supportedCurrencies=$supportedCurrencies, supportedCountries=$supportedCountries, " +
            "clientKey=$clientKey, lookupPath=$lookupPath, lookupByOrderPath=$lookupByOrderPath, lookupTimeout=$lookupTimeout, allowTestKeysInProtectedEnv=$allowTestKeysInProtectedEnv, easyPays=$easyPays)"
}

private const val DEFAULT_BASE_URL = "https://api.tosspayments.com"

/** 토스 `easyPay` 코드표(https://docs.tosspayments.com/codes/enum-codes). 오타를 기동 때 막으려고 설정을 이 표로 거른다. */
object TossEasyPays {
    val CODES = setOf("TOSSPAY", "NAVERPAY", "KAKAOPAY", "SAMSUNGPAY", "LPAY", "PAYCO", "SSG", "APPLEPAY", "PINPAY")

    /** 앞뒤 공백 · 대소문자를 정리하고 중복을 빼 순서를 지킨다. 표에 없는 코드가 있으면 던진다. */
    fun normalize(codes: List<String>): List<String> {
        val cleaned = codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val unknown = cleaned.filterNot { it in CODES }
        require(unknown.isEmpty()) { "skeleton.payment-toss.easy-pays has unknown code(s) $unknown - Toss easyPay codes: ${CODES.sorted()}" }
        return cleaned.distinct()
    }
}
