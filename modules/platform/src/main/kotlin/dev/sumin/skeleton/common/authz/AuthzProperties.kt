package dev.sumin.skeleton.common.authz

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.authz")
data class AuthzProperties(
    /** 역할 → 권한 목록. 주면 모듈 선언의 기본 매핑을 통째로 이긴다 */
    val roles: Map<String, List<String>> = emptyMap(),
    /** 전권(`*`)을 가진 단 하나의 역할 */
    val superRole: String = "ADMIN",
    /** 역할 → 권한 캐시 수명(`authz-jdbc`). 0 이면 캐시 끔 */
    val cacheTtl: Duration = Duration.ofSeconds(30),
)
