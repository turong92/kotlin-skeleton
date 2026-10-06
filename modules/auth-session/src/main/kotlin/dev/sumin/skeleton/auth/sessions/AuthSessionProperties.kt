package dev.sumin.skeleton.auth.sessions

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 리프레시 토큰 · 세션 설정. 기본값은 프레임워크 기본을 바꾸지 않는 쪽이다 — 전달 방식은 body(쿠키 · CSRF 면이 없다),
 * 동시 새로고침 유예는 0(유예 없음).
 */
@ConfigurationProperties("skeleton.auth-session")
data class AuthSessionProperties(
    /** body: 리프레시 토큰을 응답 JSON 으로 주고 요청 본문으로 받는다 (CSRF 없음) | cookie: HttpOnly 쿠키 (CSRF 헤더 요구) */
    val delivery: Delivery = Delivery.BODY,
    /** 세션의 절대 수명 — 로그인 뒤 이 시간이 지나면 새로고침해도 끝난다 */
    val absoluteTtl: Duration = Duration.ofDays(30),
    /** 마지막 새로고침 뒤 이 시간 동안 쓰지 않으면 끝난다 */
    val idleTtl: Duration = Duration.ofDays(14),
    /**
     * 쓰고 난 리프레시 토큰을 기억하는 시간 — 그 안에 다시 내밀면 "재사용"(탈취 의심)으로 세션 전체를 닫고 이벤트를 낸다.
     * 비우면(기본) **세션이 끝날 때까지** 기억한다(세션 청소가 함께 지운다). 값을 정하면 그 시간이 지난 토큰은 그냥 모르는 토큰이 되어
     * 도둑이 먼저 쓴 뒤 주인이 그보다 늦게 오면 탐지되지 않는다.
     */
    val reuseMemory: Duration? = null,
    /**
     * 방금 회전해서 쓴 옛 토큰을 이 시간 안에 다시 내밀면(응답을 잃은 브라우저 · 탭 두 개 동시 새로고침) 재사용으로 치지 않고 **같은 후속 토큰**을 돌려준다
     * (새 토큰을 또 찍지 않는다 — 갈래가 생기지 않는다). 지나서 내밀면 재사용 → 세션 종료. 0 이면 유예 없음 (모듈 기본: 중립).
     * 이 유예 안에서 도둑이 옛 토큰을 내밀어도 얻는 것은 주인이 이미 가진 후속 토큰뿐이다. 앱이 정한다 — `apps/api` · `apps/sample` 은 10s.
     */
    val reuseGrace: Duration = Duration.ZERO,
    /** 계정 하나가 가질 수 있는 세션 수 — 넘으면 가장 오래된 세션부터 닫는다 */
    val maxSessionsPerAccount: Int = 20,
    val rotation: Rotation = Rotation(),
    val cookie: Cookie = Cookie(),
    val http: Http = Http(),
    val purge: Purge = Purge(),
) {
    init {
        require(!absoluteTtl.isNegative && !absoluteTtl.isZero && !idleTtl.isNegative && !idleTtl.isZero) { "skeleton.auth-session.*-ttl must be > 0" }
        require(!reuseGrace.isNegative && reuseMemory?.isNegative != true) { "skeleton.auth-session.reuse-* must be >= 0" }
        require(maxSessionsPerAccount >= 1) { "skeleton.auth-session.max-sessions-per-account must be >= 1" }
    }

    /**
     * 세션 하나가 창 안에 새로고침(회전)할 수 있는 횟수 — 회전마다 토큰 행이 하나 늘고(재사용 탐지 때문에 세션이 끝날 때까지 남는다) 로그인한 사용자가 `/auth/refresh` 를 돌리면
     * 행이 무한히 쌓이므로 **행 수의 상한**이 이 값이다: 최대 `maxPerWindow × (세션 수명 ÷ window)`. 정상 클라이언트는 액세스 토큰 수명(15분)마다 한 번이다.
     * 넘으면 429 AUTH.TOO_MANY_REFRESHES (세션은 그대로 — 로그아웃시키지 않는다). 0 이면 끈다.
     */
    data class Rotation(val maxPerWindow: Int = 30, val window: Duration = Duration.ofMinutes(10)) {
        init { require(maxPerWindow >= 0 && !window.isNegative && !window.isZero) { "skeleton.auth-session.rotation.* must be >= 0 and window > 0" } }
    }

    enum class Delivery { BODY, COOKIE }

    data class Cookie(
        val name: String = "skeleton_refresh",
        /** 개발용 http 에서 쿠키 전달을 시험할 때만 끈다 — stage · prod 의 DeployGuard 가 false 를 문제로 본다 */
        val secure: Boolean = true,
        val sameSite: String = "Strict",
        val path: String = "/api/v1/auth",
        /** cookie 전달에서 refresh · logout 이 요구하는 요청 헤더 이름 (값 `fetch`). 다른 출처의 폼 · img 요청은 이 헤더를 못 붙인다 */
        val csrfHeader: String = "X-Requested-With",
    ) {
        init { require(sameSite.lowercase() in setOf("strict", "lax", "none")) { "skeleton.auth-session.cookie.same-site must be Strict, Lax or None" } }
    }

    data class Purge(
        /** 끝난 세션을 지우는 주기. 0 이면 주기 청소를 하지 않는다 (앱이 `SessionPurge.runOnce` 를 직접 부를 때) */
        val interval: Duration = Duration.ofHours(1),
        /** 끝난 뒤(철회 · 만료) 이 기간이 지난 세션 행을 지운다 — 그 전에는 사고 조사용으로 남는다 */
        val retention: Duration = Duration.ofDays(7),
    ) {
        init { require(!interval.isNegative && !retention.isNegative) { "skeleton.auth-session.purge.* must be >= 0" } }
    }

    data class Http(
        /** false 면 `/api/v1/auth/refresh` · `/logout` · `/sessions` 를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
        val enabled: Boolean = true,
    )
}
