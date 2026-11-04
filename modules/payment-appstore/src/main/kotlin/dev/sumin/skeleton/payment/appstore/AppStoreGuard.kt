package dev.sumin.skeleton.payment.appstore

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import java.net.URI

/**
 * 돈 낸 사람이 못 받는 배포가 정상으로 뜨지 않게 한다(토스와 같은 규칙).
 * 기동 실패: 보호 환경(stage · prod)에서 켰는데 필수 값이 비어 있음 - 이 결제사 빈이 서지 않아 앱에서 산 구매를 확인할 수 없다.
 * 경고: 보호 환경 밖에서 값이 비어 있음 / 보호 환경에서 서버 API 주소가 애플 것이 아님(인증 토큰이 그리로 간다 - 주소 값은 적지 않는다).
 * 메시지에는 **속성 이름만** 적고 값은 싣지 않는다.
 */
class AppStoreGuard(private val properties: AppStoreProperties) : DeployGuard {
    override val name: String = "payment-appstore"

    override fun problems(context: DeployContext): List<String> {
        val missing = properties.missing()
        if (!context.protectedEnv || missing.isEmpty()) return emptyList()
        return listOf("${missing.joinToString(", ")} is empty but skeleton.payment-appstore.enabled=true in a protected environment (skeleton.env=stage|prod): the App Store provider would not exist and purchases made in the app could not be verified; set the values or turn the module off")
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        val missing = properties.missing()
        // 보호 환경에서는 problems() 가 이미 기동을 막는다 - 두 번 말하지 않는다
        if (missing.isNotEmpty() && !context.protectedEnv) {
            add("skeleton.payment-appstore.enabled=true but ${missing.joinToString(", ")} is empty: the App Store provider bean is not created and in-app purchases will not be verified")
        }
        if (context.protectedEnv) {
            for ((property, url) in listOf("skeleton.payment-appstore.base-url" to properties.resolvedBaseUrl, "skeleton.payment-appstore.sandbox-base-url" to properties.resolvedSandboxBaseUrl)) {
                if (!isAppleHost(url)) add("$property is not an Apple host: requests carrying the signing token would go elsewhere; leave it at its default unless a proxy is intended")
            }
        }
    }

    private fun isAppleHost(url: String): Boolean {
        val uri = runCatching { URI.create(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && host.endsWith(AppStoreProperties.APPLE_HOST_SUFFIX)
    }
}
