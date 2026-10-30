package dev.sumin.skeleton.search

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   search:
 *     http:
 *       enabled: true               # false 면 검색 경로를 닫는다
 *       allow-anonymous-read: false # true 면 검색을 로그인 없이 연다 (기본은 로그인 필요 - 401)
 *     admin:
 *       enabled: false              # true 면 운영자 경로(search.manage)가 열린다. 꺼져 있으면 404
 *     rebuild:
 *       stale-after: PT15M          # 재색인이 이 시간 동안 진행이 없으면 멈춘 것으로 보고 다시 시작할 수 있게 한다
 * ```
 */
@ConfigurationProperties("skeleton.search")
data class SearchProperties(
    val http: Http = Http(),
    val admin: Admin = Admin(),
    val rebuild: Rebuild = Rebuild(),
) {
    data class Http(
        /** 검색 경로 - `GET /api/v1/search?q=…` */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/search",
        /**
         * 검색을 로그인 없이 연다. 기본 `false`(스켈레톤 기본은 옵션 없음 - 열기는 앱의 선택, `board` · `badge` 의 같은 이름 설정과 같다).
         * 열면 비로그인 호출자는 **주인이 없는 문서**만 본다. 속도 제한은 `skeleton.web.rate-limit` 을 따른다(검색 전용 한도는 없다) - 열면서 같이 켠다.
         */
        val allowAnonymousRead: Boolean = false,
    )

    data class Admin(
        /** 운영자 경로(`search.manage`). 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin",
    )

    data class Rebuild(
        /** 재색인이 한 쪽(500개)을 끝낼 때마다 진행 표시를 갱신한다 - 이 시간 동안 갱신이 없으면 멈춘 것으로 보고 새 재색인을 받는다 */
        val staleAfter: Duration = Duration.ofMinutes(15),
    )
}
