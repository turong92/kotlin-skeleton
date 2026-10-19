package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.payment.PaymentMode
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** 네이버페이의 두 환경 - 이 값 하나가 API 서버와 결제창 주소를 함께 정한다(개발 `dev-pay.paygate.naver.com` · `test-m.pay.naver.com` / 운영 `pay.paygate.naver.com` · `m.pay.naver.com`). */
enum class NaverPayMode(val apiBaseUrl: String, val windowBaseUrl: String) {
    DEVELOPMENT("https://dev-pay.paygate.naver.com", "https://test-m.pay.naver.com"),
    PRODUCTION("https://pay.paygate.naver.com", "https://m.pay.naver.com"),
}

@ConfigurationProperties("skeleton.payment-naverpay")
data class NaverPayProperties(
    val enabled: Boolean = false,
    val providerId: String = "naverpay",
    val clientName: String = "payment-naverpay",
    /** 개발 / 운영. 기본은 개발 - 운영은 쓰는 쪽이 일부러 고른다(보호 환경에서 개발이면 기동 실패, 로컬에서 운영이면 기동 실패). */
    val mode: NaverPayMode = NaverPayMode.DEVELOPMENT,
    /** 가맹점 식별키(Client ID) - 서버가 네이버페이를 부를 때 헤더로 싣는다(브라우저에 나가지 않는다). `client-secret` 과 짝이다(프로젝트마다 자기 것). */
    val clientId: String = "",
    /** 클라이언트 시크릿. 서버에만 있다. 비밀이라 [toString] 에 찍지 않는다. */
    val clientSecret: String = "",
    /** 가맹점 그룹 별 식별키(Chain ID) - 헤더로 싣는다. 단건과 자동결제는 값이 다르다(이 모듈은 단건). `client-secret` 과 짝이다. */
    val chainId: String = "",
    /** 비우면 [mode] 가 정한 네이버페이 주소. 프런트 e2e 의 가짜 서버나 프록시를 가리킬 때만 쓴다. */
    val baseUrl: String = "",
    val reservePath: String = "/naverpay-partner/naverpay/payments/v2/reserve",
    val applyPath: String = "/naverpay-partner/naverpay/payments/v2.2/apply/payment",
    val cancelPath: String = "/naverpay-partner/naverpay/payments/v1/cancel",
    /** 뒤에 `/{결제 번호}` 를 붙여 부른다(결제 번호로 결제 내역 조회). */
    val historyPath: String = "/naverpay-partner/naverpay/payments/v2.3/list/history",
    /** 비우면 [mode] 가 정한 결제창 주소(`https://test-m.pay.naver.com` / `https://m.pay.naver.com`) - 구매자가 `<이 주소>/payments/{결제 예약 ID}` 로 간다. 프런트 e2e 의 가짜 창을 가리킬 때만 쓴다. */
    val windowBaseUrl: String = "",
    val supportedCurrencies: Set<String> = setOf("KRW"),
    val supportedCountries: Set<String> = setOf("KR"),
    /**
     * 결제를 마친(또는 그만둔) 구매자를 돌려보낼 우리 화면의 주소(예: `https://app.example.com/payments/return`). 구매자가 정하는 값이 아니라 설정이다(열린 리다이렉트를 만들지 않는다).
     * 서버가 결제를 예약할 때 이 주소를 넘긴다. 모듈이 `kind` · `provider` · `order` 조각을 붙이고 네이버페이가 `resultCode` · `paymentId`(실패면 `resultMessage` · `reserveId`)를 이어 붙인다. 프로젝트마다 자기 것.
     */
    val returnUrl: String = "",
    /** 결제를 시작한 화면의 origin 으로 돌아오게 허락하는 origin 목록 - 뜻은 카카오페이의 같은 이름 설정과 같다. 목록 밖이거나 비어 있으면 [returnUrl] 그대로. */
    val allowedReturnOrigins: Set<String> = emptySet(),
    /** 결제 예약 한 번의 제한 시간(요청 단위). 실패하면 결제창을 열지 않는다(503). */
    val reserveTimeout: Duration = Duration.ofSeconds(10),

    /** 승인 · 취소는 문서가 60초를 권한다(최종 승인이 끝나기까지 시간이 걸린다). 클라이언트 단위 `skeleton.http.clients.payment-naverpay.response-timeout` 이 이보다 짧으면 그 값이 먼저 끊는다. */
    val applyTimeout: Duration = Duration.ofSeconds(60),
    val lookupTimeout: Duration = Duration.ofSeconds(10),
    /** 보호 환경(stage · prod)에서 개발 모드로 서는 것을 일부러 허락한다(가맹 심사 전의 시연 배포). */
    val allowDevelopmentModeInProtectedEnv: Boolean = false,
) {
    /** 이 환경의 모드를 결제 장부의 말로. */
    val paymentMode: PaymentMode get() = if (mode == NaverPayMode.PRODUCTION) PaymentMode.LIVE else PaymentMode.TEST

    /** 호출할 API 주소 - [baseUrl] 이 있으면 그것, 없으면 모드의 것. */
    val apiBaseUrl: String get() = baseUrl.trim().ifEmpty { mode.apiBaseUrl }

    /** 구매자가 갈 결제창 주소의 앞부분. */
    val windowBase: String get() = windowBaseUrl.trim().trimEnd('/').ifEmpty { mode.windowBaseUrl }

    /** 비밀과 아이디를 찍지 않는다(`KakaoPayProperties` 와 같은 방식) - 설정을 로그 · 예외 메시지에 찍어도 새지 않게. 주소는 값 대신 기본값인지만 말한다. */
    override fun toString(): String =
        "NaverPayProperties(enabled=$enabled, providerId=$providerId, clientName=$clientName, mode=$mode, clientId=${redacted(clientId)}, clientSecret=${redacted(clientSecret)}, chainId=${redacted(chainId)}, " +
            "baseUrl=${if (baseUrl.isBlank()) "<default>" else "<set>"}, supportedCurrencies=$supportedCurrencies, supportedCountries=$supportedCountries, " +
            "returnUrl=${if (returnUrl.isBlank()) "<unset>" else "<set>"}, allowedReturnOrigins=${allowedReturnOrigins.size}, applyTimeout=$applyTimeout, lookupTimeout=$lookupTimeout, allowDevelopmentModeInProtectedEnv=$allowDevelopmentModeInProtectedEnv)"

    private fun redacted(value: String) = if (value.isBlank()) "<unset>" else "<redacted>"
}
