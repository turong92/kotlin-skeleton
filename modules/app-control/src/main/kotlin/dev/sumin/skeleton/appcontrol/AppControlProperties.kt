package dev.sumin.skeleton.appcontrol

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 재배포 없이 끄고 켜는 것들 - 기능 스위치 · 점검 모드 · 모바일 최소 버전. 모듈 기본은 꺼짐 - 앱 yml 이 켠다. 값은 DB 에 있고 운영자 경로로 바꾼다(저장소는 `app-control-jdbc`).
 * ```yaml
 * skeleton:
 *   app-control:
 *     enabled: true
 *     config: { enabled: true }   # 클라이언트가 읽는 GET /api/v1/app/config
 *     admin: { enabled: true }    # 운영자 경로
 * ```
 */
@ConfigurationProperties("skeleton.app-control")
data class AppControlProperties(
    val enabled: Boolean = false,
    /** 읽은 값을 이 시간 동안 메모리에 둔다 - 다른 인스턴스에서 바꾼 값은 늦어도 이 시간 뒤에 보인다. 바꾼 인스턴스는 바로 본다 */
    val cacheTtl: Duration = Duration.ofSeconds(5),
    val maintenance: Maintenance = Maintenance(),
    val config: Config = Config(),
    val admin: Admin = Admin(),
) {
    data class Maintenance(
        /** 점검 중 `Retry-After` — 점검 끝 예정 시각이 없을 때 쓴다 (끝 예정이 있으면 그때까지 남은 초) */
        val retryAfter: Duration = Duration.ofMinutes(5),
        /** 점검 중에도 통과하는 경로(PathPattern). 이 모듈의 설정 경로 · 운영자 경로는 여기 적지 않아도 통과한다. 운영자가 로그인해 점검을 끌 수 있게 로그인 경로가 들어 있다 */
        val passPaths: List<String> = listOf("/health/**", "/info", "/actuator/health/**", "/api/v1/auth/**", "/api/v1/admin/**"),
    )

    data class Config(
        /** true 면 클라이언트가 읽는 설정 경로가 열린다 (익명 · 꺼져 있으면 경로가 없어 404) */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/app/config",
    )

    data class Admin(
        /** true 면 운영자 경로가 열린다 (꺼져 있으면 경로가 없어 404) */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin/app-control",
    )
}
