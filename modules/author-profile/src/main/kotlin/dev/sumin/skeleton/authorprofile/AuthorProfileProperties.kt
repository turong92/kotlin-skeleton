package dev.sumin.skeleton.authorprofile

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 이름 규칙 · 겹침 방식(`uniqueness`) · 예약어 · 이름 바꾸기 한도는 여기 없다 — 앱에 닉네임 정책은 하나라서 `skeleton.account.display-name.*` 를 그대로 따른다.
 */
@ConfigurationProperties("skeleton.author-profile")
data class AuthorProfileProperties(
    /** 계정당 **살아 있는** 프로필 수 — 넘으면 `409 AUTHOR_PROFILE.LIMIT_REACHED`. 지우면 자리가 난다 */
    val maxPerAccount: Int = 5,
    /** 한 계정이 [createWindow] 안에 프로필을 **만들 수 있는** 횟수 — 지우고 다시 만드는 것을 막는다. 넘으면 `429 AUTHOR_PROFILE.RATE_LIMITED`. 0 이면 한도 없음 */
    val createLimit: Int = 5,
    val createWindow: Duration = Duration.ofDays(30),
    /** true 면 기동할 때 옛 글(`AuthorAdoptionListener` 가 가진 공개 작성자 id 없는 행)을 계정의 첫 프로필로 넘겨받는다 */
    val adoptOnStartup: Boolean = true,
    val http: Http = Http(),
    val admin: Admin = Admin(),
) {
    init {
        require(maxPerAccount >= 1) { "skeleton.author-profile.max-per-account must be >= 1" }
        require(createLimit >= 0) { "skeleton.author-profile.create-limit must be >= 0 (0 = no limit)" }
        require(!createWindow.isZero && !createWindow.isNegative) { "skeleton.author-profile.create-window must be positive" }
    }

    data class Http(
        /** false 면 `/author-profiles` 엔드포인트를 등록하지 않는다 */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/author-profiles",
    ) {
        init { require(basePath.startsWith("/")) { "skeleton.author-profile.http.base-path must start with '/'" } }
    }

    data class Admin(
        /** 관리자의 프로필 ↔ 계정 연결 조회 엔드포인트 — 기본 꺼짐. 켜면 호출마다 감사 이벤트가 남는다 */
        val enabled: Boolean = false,
    )
}
