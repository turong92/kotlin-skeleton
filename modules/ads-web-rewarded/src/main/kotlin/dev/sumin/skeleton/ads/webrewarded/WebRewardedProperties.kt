package dev.sumin.skeleton.ads.webrewarded

import java.time.Duration
import java.time.ZoneId
import org.springframework.boot.context.properties.ConfigurationProperties

/** `skeleton.ads-web-rewarded` — 웹 보상형 광고 세션. 기본 꺼짐, 상한 없음, 보상 종류 없음(= 1회용 토큰 모드). */
@ConfigurationProperties("skeleton.ads-web-rewarded")
data class WebRewardedProperties(
    /** true 여야 두 경로가 선다(꺼짐 → 404). 저장소(`ads-web-rewarded-jdbc`)가 있어야 한다. */
    val enabled: Boolean = false,
    /** 세션을 열고 보상 요청까지 허용하는 시간. */
    val sessionTtl: Duration = Duration.ofMinutes(15),
    /** 세션을 열고 이보다 빨리 온 보상 요청은 거절 — 구글: 표시 광고는 7초 이상 보여야 보상. */
    val minElapsed: Duration = Duration.ofSeconds(7),
    /** 계정 하루 보상 횟수. 없으면(기본) 상한 없음. */
    val accountDailyLimit: Long? = null,
    /** IP 하루 보상 횟수. 없으면(기본) 상한 없음. */
    val ipDailyLimit: Long? = null,
    /** 「하루」의 기준 시간대(`ZoneId.of` 가 받는 이름) — 이 시간대의 자정에 상한이 리셋된다. */
    val dayZone: String = "UTC",
    /** 보상 즉시 이 종류의 `RewardIssuer` 를 부른다. 없으면(기본) 1회용 토큰을 돌려준다. */
    val rewardType: String? = null,
    /** 지급기에 넘길 params. */
    val rewardParams: Map<String, String> = emptyMap(),
    /** 토큰 모드에서 토큰을 쓸 수 있는 시간. */
    val tokenTtl: Duration = Duration.ofMinutes(10),
) {
    init {
        require(sessionTtl.isPositive) { "skeleton.ads-web-rewarded.session-ttl must be positive" }
        require(minElapsed.isPositive) { "skeleton.ads-web-rewarded.min-elapsed must be positive" }
        require(tokenTtl.isPositive) { "skeleton.ads-web-rewarded.token-ttl must be positive" }
        require(minElapsed < sessionTtl) { "skeleton.ads-web-rewarded.min-elapsed must be shorter than session-ttl" }
        require(accountDailyLimit == null || accountDailyLimit >= 0) { "skeleton.ads-web-rewarded.account-daily-limit must not be negative" }
        require(ipDailyLimit == null || ipDailyLimit >= 0) { "skeleton.ads-web-rewarded.ip-daily-limit must not be negative" }
        require(runCatching { ZoneId.of(dayZone) }.isSuccess) { "skeleton.ads-web-rewarded.day-zone must be a zone id ZoneId.of accepts" }
    }
}
