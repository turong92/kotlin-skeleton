package dev.sumin.skeleton.badge

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   badge:
 *     http:
 *       enabled: true               # false 면 사용자 경로(내 배지 · 프로필 배지)를 닫는다
 *       allow-anonymous-read: false # true 면 프로필 배지 경로를 로그인 없이 읽는다 (기본은 로그인 필요 - 401)
 *     admin:
 *       enabled: false              # true 면 운영자 경로(badge.manage)가 열린다. 꺼져 있으면 404
 *     notify:
 *       enabled: true               # notification 이 있을 때만 뜻이 있다
 * ```
 */
@ConfigurationProperties("skeleton.badge")
data class BadgeProperties(
    val admin: Admin = Admin(),
    val http: Http = Http(),
    val notify: Notify = Notify(),
) {
    data class Admin(
        /** 운영자 경로(`badge.manage`). 기본 꺼짐 → 경로가 없어 404 */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin",
    )

    data class Http(
        /** 사용자 경로 — `GET /api/v1/badges/me` · `PATCH /api/v1/badges/me/{awardId}` · `GET /api/v1/badges/accounts/{accountId}` */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/badges",
        /**
         * 프로필 배지(`GET {basePath}/accounts/{accountId}`)를 로그인 없이 읽게 연다. 기본 `false`(스켈레톤 기본은 옵션 없음 - 열기는 앱의 선택, `board` 의 같은 이름 설정과 같다).
         * 열면 응답은 `code · name · description · imageUrl` 넷뿐이다. 속도 제한은 `skeleton.web.rate-limit` 을 따른다(배지 전용 한도는 없다) - 열면서 같이 켠다.
         */
        val allowAnonymousRead: Boolean = false,
    )

    data class Notify(
        /** false 면 받았어요 알림을 보내지 않는다 */
        val enabled: Boolean = true,
        /** 알림 주제 */
        val topic: String = "badges",
    )
}
