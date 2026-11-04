package dev.sumin.skeleton.payment.googleplay

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.payment-googleplay` — Google Play Developer API · 실시간 개발자 알림(RTDN, Pub/Sub push). 네 값([packageName] · [serviceAccount] · [Pubsub.audience] · [Pubsub.serviceAccountEmail])이
 * 모두 있고 서비스 계정을 읽을 수 있어야 결제사 빈이 선다. 비밀([serviceAccount])은 [toString] 에 찍지 않는다.
 */
@ConfigurationProperties("skeleton.payment-googleplay")
data class GooglePlayProperties(
    val enabled: Boolean = false,
    val providerId: String = "googleplay",
    val clientName: String = "payment-googleplay",
    /** 앱의 패키지 이름 - 구글 API 는 이 경로로만 묻고, RTDN 의 `packageName` 이 다르면 무시한다. */
    val packageName: String = "",
    /** 서비스 계정 키(JSON)의 base64 - 비밀. Play Console 에서 이 계정에 「재무 데이터 보기」 · 「주문 및 구독 관리」 권한을 준다. */
    val serviceAccount: String = "",
    /** Pub/Sub push 구독의 인증(OIDC 토큰) 설정 - 구독을 만들 때 정한 값과 같아야 한다. */
    val pubsub: Pubsub = Pubsub(),
    /** Google Play Developer API 주소. 비우면 구글 것. 시험의 가짜 서버는 여기에 적는다. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 서비스 계정 접근 토큰 주소. 구글 것으로 고정 - 시험만 바꾼다(보호 환경에서 바뀌어 있으면 기동 실패: 서명한 자격이 그리로 간다). 서비스 계정 파일의 `token_uri` 는 읽지 않는다. */
    val tokenUrl: String = DEFAULT_TOKEN_URL,
    /** push 토큰을 확인하는 구글 공개키(JWKS) 주소. 구글 것으로 고정 - 시험만 바꾼다(보호 환경에서 바뀌어 있으면 기동 실패: 알림 위조 방어가 풀린다). */
    val certsUrl: String = DEFAULT_CERTS_URL,
    /** 구글 API 한 번 호출(토큰 · 조회 · 승인 통지)의 제한 시간. */
    val lookupTimeout: Duration = Duration.ofSeconds(5),
) {
    data class Pubsub(
        /** push 구독에 적은 토큰 대상(`audience`) - 보통 이 서버의 알림 주소. */
        val audience: String = "",
        /** push 구독이 토큰을 만들 때 쓰는 서비스 계정의 이메일. */
        val serviceAccountEmail: String = "",
    )

    val resolvedBaseUrl: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }
    val resolvedTokenUrl: String get() = tokenUrl.ifBlank { DEFAULT_TOKEN_URL }
    val resolvedCertsUrl: String get() = certsUrl.ifBlank { DEFAULT_CERTS_URL }

    /** 비어 있는 필수 값의 속성 이름(`skeleton.payment-googleplay.…`) - 비었으면 이 결제사는 서지 않는다. */
    fun missing(): List<String> = listOf(
        "package-name" to packageName, "service-account" to serviceAccount, "pubsub.audience" to pubsub.audience, "pubsub.service-account-email" to pubsub.serviceAccountEmail,
    ).filter { it.second.isBlank() }.map { "$PREFIX.${it.first}" }

    override fun toString(): String =
        "GooglePlayProperties(enabled=$enabled, providerId=$providerId, packageName=$packageName, serviceAccount=${if (serviceAccount.isBlank()) "<unset>" else "<redacted>"})"

    companion object {
        const val PREFIX = "skeleton.payment-googleplay"
        const val DEFAULT_BASE_URL = "https://androidpublisher.googleapis.com"
        const val DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val DEFAULT_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        const val GOOGLE_HOST_SUFFIX = ".googleapis.com"
    }
}
