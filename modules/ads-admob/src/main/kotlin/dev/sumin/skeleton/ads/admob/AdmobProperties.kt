package dev.sumin.skeleton.ads.admob

import java.time.Duration
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
    }
}
