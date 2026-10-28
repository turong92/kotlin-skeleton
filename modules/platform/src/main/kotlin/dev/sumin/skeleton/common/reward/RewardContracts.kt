package dev.sumin.skeleton.common.reward

import java.util.Locale

/**
 * 보상 한 종류를 실제로 주는 쪽의 계약 — `coupon`(쿠폰 발급) · `point`(포인트 적립)이 구현하고, `reward` 의 일꾼이 지급 건마다 부른다.
 * 이벤트(`campaign`)는 이것을 모른다 — 「어떤 종류를 어떤 params 로」만 적고, 줄 수 있는지는 지급기가 정한다.
 */
interface RewardIssuer {
    /** 보상 종류 이름 (`coupon` · `point` …). 앱 안에서 하나 */
    val type: String

    /** 이벤트 설정을 저장하기 전에 [params] 가 이 종류로 줄 수 있는 모양인지 본다. 아니면 [IllegalArgumentException] */
    fun validate(params: Map<String, String>)

    /**
     * 지급 건 하나를 준다. **호출자 트랜잭션 안**에서 불리고, `(GRANT, grant.id)` 로 멱등해야 한다(일꾼이 같은 건을 두 번 불러도 한 번만 준다).
     * 다시 해 봐야 소용없는 실패(정의 보관됨 …)는 [RewardPermanentFailure] 로 알린다.
     */
    fun issue(grant: RewardGrantView): IssuedReward

    /** 받은편지함 알림 · 운영 화면에 보일 문구 */
    fun describe(params: Map<String, String>, locale: Locale): RewardLabel
}

/** 지급 건의 읽기 모양 — 지급기가 보는 것 */
data class RewardGrantView(
    val id: String,
    val accountId: String,
    val type: String,
    val params: Map<String, String>,
    val sourceType: String,
    val sourceKey: String,
)

/** 지급 결과. [resultRef] 는 만들어진 것의 id(쿠폰 id · 포인트 내역 id) — 지급 건 줄에 남는다 */
data class IssuedReward(val resultRef: String)

data class RewardLabel(val title: String, val message: String, val link: String? = null)

/** 지급기가 「다시 해도 같다」고 알리는 실패 — 재시도 없이 지급 건이 `FAILED` 로 남는다 */
class RewardPermanentFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
