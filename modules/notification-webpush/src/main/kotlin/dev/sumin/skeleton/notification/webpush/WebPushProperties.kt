package dev.sumin.skeleton.notification.webpush

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 웹 푸시(VAPID) 전달 수단. 모듈 기본은 꺼짐 — 앱 yml 이 켠다. VAPID 키 쌍은 **한 번 만들어 고정한다**(바꾸면 기존 브라우저 구독이 모두 못 쓰게 된다).
 * ```yaml
 * skeleton:
 *   notification:
 *     webpush:
 *       enabled: true
 *       public-key: <npx web-push generate-vapid-keys 의 Public Key>
 *       private-key: ${...}   # 운영자 비밀 - 환경변수로만
 *       subject: mailto:ops@example.com
 * ```
 */
@ConfigurationProperties("skeleton.notification.webpush")
data class WebPushProperties(
    val enabled: Boolean = false,
    /** VAPID 공개 키(base64url, 비압축 P-256 65바이트). 비밀이 아니다 - 화면이 구독할 때 `applicationServerKey` 로 쓴다 */
    val publicKey: String = "",
    /** VAPID 개인 키(base64url 32바이트) - **운영자 비밀**. 로그 · 응답 · toString 어디에도 나오지 않는다 */
    val privateKey: String = "",
    /** VAPID `sub` - `mailto:` 또는 `https:` 연락처. Apple 은 없으면 거절한다 */
    val subject: String = "",
    /**
     * 구독 끝점으로 허용할 푸시 서비스 호스트(SSRF 방어). `host` 정확히 · `*.suffix`(그 아래 모든 호스트) · `http://host:port`(개발 · e2e 가짜 푸시 서비스 전용 - 보호 환경에서는 기동 실패).
     * 끝점은 어느 경우든 https · 443 이어야 하고(위 `http://` 항목만 예외), 사설 · 내부 주소로 풀리는 호스트와 리다이렉트는 따라가지 않는다
     */
    val allowedHosts: List<String> = DEFAULT_ALLOWED_HOSTS,
    /** 푸시 서비스 한 번 호출의 시간 제한 */
    val timeout: Duration = Duration.ofSeconds(10),
) {
    /** 개인 키가 어디에도 새지 않게 - 데이터 클래스의 자동 toString 을 쓰지 않는다 */
    override fun toString(): String = "WebPushProperties(enabled=$enabled)"

    companion object {
        /** Chrome(FCM) · Firefox · Safari 16+ · Edge(WNS) */
        val DEFAULT_ALLOWED_HOSTS: List<String> = listOf(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "*.push.apple.com",
            "*.notify.windows.com",
        )
    }
}

/**
 * 견본용으로 알려진 시험 공개 키. 개인 키 짝이 공개 문서에 있으므로 **보호 환경(stage · prod)에서 이 키면 기동이 실패한다**.
 * 로컬 · 시험에서만 쓴다 - 실제 서비스 키는 `npx web-push generate-vapid-keys` 로 따로 만든다.
 */
object WebPushTestKeys {
    const val PUBLIC_KEY = "BKHphhcs7lo2uPUj_8zQArEHejEcaV66-4IrYRYiszNilMwvkTXFwgoOT-hfcoj2w_AYh29HQo57wFQIBYRhljY"
}
