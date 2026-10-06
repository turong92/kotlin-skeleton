package dev.sumin.skeleton.common.consent

/** 가입자가 "이 판을 보고 동의한다" 고 말한 한 건. [locale] 은 실제로 읽은 언어(없으면 앱 기본) */
data class ConsentClaim(val type: String, val version: String, val locale: String? = null)

/** 동의가 어디서 · 어떤 클라이언트에서 나왔나 — 저장할지는 동의 모듈 설정이 정한다 */
data class ConsentContext(val ip: String?, val userAgent: String?)

/**
 * 가입에 약관 동의를 묶는 고리 — `account` 가 부르고 `legal` 이 구현한다. 두 모듈은 서로를 모르고(둘 다 platform 만 안다) 빈이 없으면 `account` 는 동의를 다루지 않는다.
 *
 * - [check]: 가입 시도를 **열 때**, 주소와 무관하게(요청 본문만 보고) 부른다. 동의가 모자라거나 낡았으면 던진다(ApplicationException, `LEGAL.CONSENT_REQUIRED`).
 *   주소를 보지 않으므로 응답으로 주소가 드러나지 않는다.
 * - [record]: 시도가 **확인되어 계정이 만들어지는 같은 트랜잭션에서** 부른다. 확인되지 않은 시도는 동의 기록을 남기지 않는다.
 */
interface SignUpConsentGate {
    fun check(claims: List<ConsentClaim>)

    fun record(accountId: String, claims: List<ConsentClaim>, context: ConsentContext)
}
