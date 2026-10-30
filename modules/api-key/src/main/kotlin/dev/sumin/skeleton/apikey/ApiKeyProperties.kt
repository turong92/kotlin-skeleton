package dev.sumin.skeleton.apikey

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   api-key:
 *     token-prefix: apk          # 토큰은 <접두사>_<무작위 32자> - 앱마다 달리해 비밀 검출 규칙을 걸 수 있게
 *     max-active-per-owner: 10   # 주인 하나의 활성 키 상한(자원 보호)
 *     max-lifetime:              # 비면 만료 상한 없음. 주면 만료일이 필수이고 이 기간을 넘을 수 없다
 *     rate-limit:
 *       capacity: 0              # 0 = 끔. 키마다 window 안에 이 횟수까지
 *       window: 1m
 *     http:
 *       enabled: true            # false 면 사용자 경로(발급 · 목록 · 회수 · 범위 목록)를 닫는다
 *     admin:
 *       enabled: false           # true 면 운영자 경로(전체 목록 · 운영 회수) - 권한 api-key.read · api-key.revoke
 * ```
 */
@ConfigurationProperties("skeleton.api-key")
data class ApiKeyProperties(
    val tokenPrefix: String = "apk",
    val maxActivePerOwner: Int = 10,
    val maxLifetime: Duration? = null,
    val rateLimit: RateLimit = RateLimit(),
    val http: Http = Http(),
    val admin: Admin = Admin(),
) {
    init {
        require(PREFIX.matches(tokenPrefix)) { "skeleton.api-key.token-prefix must match ${PREFIX.pattern}: $tokenPrefix" }
        require(maxActivePerOwner >= 1) { "skeleton.api-key.max-active-per-owner must be at least 1" }
        require(maxLifetime == null || (!maxLifetime.isNegative && !maxLifetime.isZero)) { "skeleton.api-key.max-lifetime must be positive" }
    }

    data class RateLimit(
        /** 키 하나가 [window] 안에 할 수 있는 요청 수. 0 이면 끈다(모듈은 사용 방식을 정하지 않는다 - 앱이 켠다) */
        val capacity: Int = 0,
        val window: Duration = Duration.ofMinutes(1),
    ) {
        init {
            require(capacity >= 0) { "skeleton.api-key.rate-limit.capacity must not be negative" }
            require(!window.isNegative && !window.isZero) { "skeleton.api-key.rate-limit.window must be positive" }
        }
    }

    data class Http(
        /** `GET · POST /api/v1/api-keys` · `DELETE …/{id}` · `GET …/scopes` */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/api-keys",
    )

    data class Admin(
        /** 운영자 경로 `GET /admin/api-keys` · `POST /admin/api-keys/{id}/revoke` - 모듈 기본은 꺼짐(404). 권한 `api-key.read` · `api-key.revoke` */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin/api-keys",
    )

    companion object {
        /** 소문자로 시작하는 2~16자 - 힌트(`접두사…끝 4자`)가 24자 열에 들어간다 */
        val PREFIX = Regex("^[a-z][a-z0-9]{1,15}$")
    }
}
