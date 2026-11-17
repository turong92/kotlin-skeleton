package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.common.time.DayWindow
import dev.sumin.skeleton.point.PointEarnLimit
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.springframework.boot.context.properties.ConfigurationProperties

/** `skeleton.ads-admob` — AdMob 보상형 광고의 서버 측 확인(SSV) 콜백 수신. 기본 꺼짐. */
@ConfigurationProperties("skeleton.ads-admob")
data class AdmobProperties(
    /** true 여야 `GET /api/v1/ads/admob/ssv` 가 선다(꺼짐 → 404). */
    val enabled: Boolean = false,
    /** 포인트를 쌓아도 되는 광고 단위 — AdMob 콘솔의 광고 단위 ID(`ca-app-pub-…/123`)를 그대로 넣으면 된다(콜백은 `/` 뒤 숫자만 보내므로 그 부분으로 맞춰 본다). 비면 모든 콜백을 거절한다 — 서명은 「구글이 보냈다」만 증명하므로, 남의 AdMob 계정 광고 단위가 이 주소를 콜백으로 지정해도 막는다. */
    val adUnits: List<String> = emptyList(),
    /** 적립한 포인트의 유효기간(일). 없으면 안 사라진다(point 와 같은 규칙). */
    val expiresInDays: Long? = null,
    /** 계정 하루 적립 상한(포인트 합). 없으면(기본) 상한 없음. 넘는 콜백은 적립하지 않고 200 으로 답한다 — 400 · 503 이면 구글이 같은 콜백을 다시 보낸다. */
    val dailyLimit: Long? = null,
    /** 「하루」의 기준 시간대(`ZoneId.of` 가 받는 이름 — IANA 이름 · 오프셋, 기본 UTC) — 이 시간대의 자정에 상한이 리셋된다. */
    val dayZone: String = "UTC",
    /** 구글의 서명 공개키 목록 주소. */
    val keysUrl: String = "https://www.gstatic.com/admob/reward/verifier-keys.json",
    /** 읽어 둔 공개키를 쥐는 시간 — 구글 안내대로 24시간을 넘기지 않는다. */
    val keyCacheTtl: Duration = Duration.ofHours(24),
    /** 공개키 목록을 읽는 한 번의 제한 시간. */
    val keyFetchTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!keyCacheTtl.isNegative && !keyCacheTtl.isZero && keyCacheTtl <= Duration.ofHours(24)) { "skeleton.ads-admob.key-cache-ttl must be positive and at most 24h" }
        require(expiresInDays == null || expiresInDays in 1..36_500) { "skeleton.ads-admob.expires-in-days must be 1..36500" }
        require(dailyLimit == null || dailyLimit >= 0) { "skeleton.ads-admob.daily-limit must not be negative" }
        require(runCatching { ZoneId.of(dayZone) }.isSuccess) { "skeleton.ads-admob.day-zone must be a zone id ZoneId.of accepts" }
    }

    /** [now] 가 든 「하루」 — [dayZone] 의 자정부터 다음 자정 전까지 `[from, to)` */
    fun dayWindow(now: Instant): DayWindow = DayWindow.containing(now, ZoneId.of(dayZone))

    /** 상한 — [dailyLimit] 이 없으면 null. 「하루」는 지갑이 적립에 쓰는 시각([dev.sumin.skeleton.point.PointWallet.earnWithin] 의 now)으로 정한다(자정을 걸쳐도 같은 시각을 쓴다) */
    fun limitAt(): ((Instant) -> PointEarnLimit)? = dailyLimit?.let { max -> { now -> dayWindow(now).let { (from, to) -> PointEarnLimit(max, from, to) } } }
}
