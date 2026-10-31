package dev.sumin.skeleton.notification.fcm

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모바일 푸시(FCM HTTP v1) 전달 수단. 모듈 기본은 꺼짐 — 앱 yml 이 켠다.
 * ```yaml
 * skeleton:
 *   notification:
 *     fcm:
 *       enabled: true
 *       credentials: ${...}   # 서비스 계정 JSON 의 base64 - 운영자 비밀, 환경변수로만
 * ```
 */
@ConfigurationProperties("skeleton.notification.fcm")
data class FcmProperties(
    val enabled: Boolean = false,
    /** Firebase 서비스 계정 키(JSON)의 base64 - **운영자 비밀**. 로그 · 응답 · toString · 실패 이유 어디에도 나오지 않는다. 프로젝트 id 도 이 안의 `project_id` 를 쓴다 */
    val credentials: String = "",
    /** 구글 서버 한 번 호출(토큰 발급 · 보내기)의 시간 제한 */
    val timeout: Duration = Duration.ofSeconds(10),
) {
    /** 서비스 계정이 어디에도 새지 않게 - 데이터 클래스의 자동 toString 을 쓰지 않는다 */
    override fun toString(): String = "FcmProperties(enabled=$enabled)"
}
