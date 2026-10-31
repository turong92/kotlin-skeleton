package dev.sumin.skeleton.notification.webpush

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * 웹 푸시를 켠 채 잘못된 설정으로 보호 환경(stage · prod)이 뜨지 않게 한다. 메시지에는 속성 · 환경변수 이름만 적는다(키 값 없음).
 * 기동 실패: 개인 키 · 공개 키 · subject 가 비었거나 읽을 수 없음 / 두 키가 한 쌍이 아님 / 견본용 시험 공개 키([WebPushTestKeys.PUBLIC_KEY]) /
 * 허용 목록에 `http://` 항목(개발 · e2e 가짜 푸시 서비스용) / 허용 목록에 아무것에도 맞지 않는 항목.
 * 보호 환경 밖에서는 같은 이유를 경고로만 남기고, 쓸 수 없는 설정이면 센더를 세우지 않는다(기기 등록이 400 으로 거절된다).
 */
class WebPushDeployGuard(private val properties: WebPushProperties) : DeployGuard {
    override val name: String = "notification-webpush"

    override fun problems(context: DeployContext): List<String> = if (context.protectedEnv) findings() else emptyList()

    override fun warnings(context: DeployContext): List<String> = if (context.protectedEnv) emptyList() else findings()

    private fun findings(): List<String> = buildList {
        addAll(WebPushConfigCheck.problems(properties))
        if (properties.publicKey.trim() == WebPushTestKeys.PUBLIC_KEY) {
            add("${WebPushConfigCheck.PUBLIC_PROPERTY} is the well-known sample test key (its private half is public): generate your own pair with 'npx web-push generate-vapid-keys' and keep the private key as an operator secret")
        }
        val http = WebPushEndpointPolicy.httpEntries(properties.allowedHosts)
        if (http.isNotEmpty()) {
            add("${WebPushConfigCheck.ALLOWED_PROPERTY} has ${http.size} http:// entr${if (http.size == 1) "y" else "ies"} (dev and e2e fake push services only): subscription endpoints would be allowed over plain http to arbitrary host:port, remove them")
        }
    }
}
