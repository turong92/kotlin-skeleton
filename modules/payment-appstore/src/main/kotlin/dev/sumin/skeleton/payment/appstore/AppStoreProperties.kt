package dev.sumin.skeleton.payment.appstore

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.payment-appstore` — App Store Server API · Notifications V2. 다섯 값([bundleId] · [appAppleId] · [issuerId] · [keyId] · [privateKey])이 모두 있어야 결제사 빈이 선다.
 * 비밀([privateKey])은 [toString] 에 찍지 않는다.
 */
@ConfigurationProperties("skeleton.payment-appstore")
data class AppStoreProperties(
    val enabled: Boolean = false,
    val providerId: String = "appstore",
    val clientName: String = "payment-appstore",
    /** 앱의 번들 id - 애플이 서명한 거래 · 알림의 `bundleId` 가 이것과 같아야 한다. */
    val bundleId: String = "",
    /** 앱의 App Store 숫자 id(`appAppleId`) - 운영 알림 · 구독 상태 응답이 이것과 같아야 한다. */
    val appAppleId: String = "",
    /** App Store Connect 의 In-App Purchase 키 발급자 id(인증 JWT 의 `iss`). */
    val issuerId: String = "",
    /** 그 키의 id(인증 JWT 헤더의 `kid`). */
    val keyId: String = "",
    /** 그 키(.p8)의 PKCS#8 PEM 본문 - 비밀. 줄바꿈이 `\n` 두 글자로 들어와도 읽는다. */
    val privateKey: String = "",
    /** 운영 서버 API 주소. 비우면 애플 것. 시험의 가짜 서버는 여기에 적는다. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 샌드박스 서버 API 주소(운영이 거래를 모른다고 하면 여기에 묻는다). 비우면 애플 것. */
    val sandboxBaseUrl: String = DEFAULT_SANDBOX_BASE_URL,
    /** 거래 · 구독 조회(요청 한 번)의 제한 시간. */
    val lookupTimeout: Duration = Duration.ofSeconds(5),
) {
    val resolvedBaseUrl: String get() = baseUrl.ifBlank { DEFAULT_BASE_URL }
    val resolvedSandboxBaseUrl: String get() = sandboxBaseUrl.ifBlank { DEFAULT_SANDBOX_BASE_URL }

    /** 비어 있는 필수 값의 속성 이름(`skeleton.payment-appstore.…`) - 비었으면 이 결제사는 서지 않는다. */
    fun missing(): List<String> = listOf(
        "bundle-id" to bundleId, "app-apple-id" to appAppleId, "issuer-id" to issuerId, "key-id" to keyId, "private-key" to privateKey,
    ).filter { it.second.isBlank() }.map { "skeleton.payment-appstore.${it.first}" }

    override fun toString(): String =
        "AppStoreProperties(enabled=$enabled, providerId=$providerId, bundleId=$bundleId, appAppleId=$appAppleId, issuerId=${mark(issuerId)}, keyId=${mark(keyId)}, privateKey=${mark(privateKey)})"

    private fun mark(value: String) = if (value.isBlank()) "<unset>" else "<redacted>"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.storekit.apple.com"
        const val DEFAULT_SANDBOX_BASE_URL = "https://api.storekit-sandbox.apple.com"
        const val APPLE_HOST_SUFFIX = ".apple.com"
    }
}
