package dev.sumin.skeleton.common.authz

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.authz")
data class AuthzProperties(
    /** 역할 → 권한 목록. 주면 모듈 선언의 기본 매핑을 통째로 이긴다 */
    val roles: Map<String, List<String>> = emptyMap(),
    /** 역할 → 물려받는 역할 목록(위가 아래의 권한을 모두 얻는다). `authz-jdbc` 에서는 첫 기동 씨앗이다 */
    val roleIncludes: Map<String, List<String>> = emptyMap(),
    /** 전권(`*`)을 가진 단 하나의 역할 */
    val superRole: String = "ADMIN",
    /** 역할 → 권한 캐시 수명(`authz-jdbc`). 0 이면 캐시 끔 */
    val cacheTtl: Duration = Duration.ofSeconds(30),
)
