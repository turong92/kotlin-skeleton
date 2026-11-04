package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import java.net.URI

/**
 * 돈 낸 사람이 못 받는 배포가 정상으로 뜨지 않게 한다(토스 · App Store 와 같은 규칙).
 * 기동 실패: 보호 환경(stage · prod)에서 켰는데 필수 값이 비었거나 서비스 계정을 읽을 수 없음 / 토큰 주소 · 인증서(공개키) 주소가 구글 것이 아님(서명한 자격이 그리로 간다 · 알림 위조 방어가 풀린다).
 * 경고: 보호 환경 밖에서 값이 비었음 / 보호 환경에서 API 주소가 구글 것이 아님.
 * 메시지에는 **속성 이름만** 적고 값은 싣지 않는다.
 */
class GooglePlayGuard(private val properties: GooglePlayProperties) : DeployGuard {
    override val name: String = "payment-googleplay"

    override fun problems(context: DeployContext): List<String> {
        if (!context.protectedEnv) return emptyList()
        return buildList {
            val config = GooglePlayConfigCheck.problems(properties)
            if (config.isNotEmpty()) {
                add("${config.joinToString("; ")} but ${GooglePlayProperties.PREFIX}.enabled=true in a protected environment (skeleton.env=stage|prod): the Google Play provider would not exist and purchases made in the app could not be verified; set the values or turn the module off")
            }
            for ((property, url) in listOf("token-url" to properties.resolvedTokenUrl, "certs-url" to properties.resolvedCertsUrl)) {
                if (!isGoogleHost(url)) add("${GooglePlayProperties.PREFIX}.$property is not a Google address: it is fixed in production (the signed service account credential would go elsewhere / push tokens would be checked against someone else's keys); leave it empty")
            }
        }
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        val config = GooglePlayConfigCheck.problems(properties)
        // 보호 환경에서는 problems() 가 이미 기동을 막는다 - 두 번 말하지 않는다
        if (config.isNotEmpty() && !context.protectedEnv) {
            add("${GooglePlayProperties.PREFIX}.enabled=true but ${config.joinToString("; ")}: the Google Play provider bean is not created and in-app purchases will not be verified")
        }
        if (context.protectedEnv && !isGoogleHost(properties.resolvedBaseUrl)) {
            add("${GooglePlayProperties.PREFIX}.base-url is not a Google host: requests carrying the access token would go elsewhere; leave it at its default unless a proxy is intended")
        }
    }

    private fun isGoogleHost(url: String): Boolean {
        val uri = runCatching { URI.create(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && host.endsWith(GooglePlayProperties.GOOGLE_HOST_SUFFIX)
    }
}
