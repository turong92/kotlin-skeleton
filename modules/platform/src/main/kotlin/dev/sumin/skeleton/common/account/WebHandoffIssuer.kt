package dev.sumin.skeleton.common.account

/**
 * 로그인한 계정을 위해 **한 번 쓰는 웹 로그인 코드**를 낸다(앱 -> 웹으로 넘어갈 때). `auth-web-handoff` 가 구현하고, 쓰는 모듈(예: `payment-iap`)은 이 계약만 안다 - 빈이 없으면 그 기능이 없다.
 * 원문은 이 반환값에만 있다 - 호출자가 주소의 `#` 조각에 실어 앱에 돌려주고(조각은 서버로 가지 않는다) 어디에도 남기지 않는다. 같은 계정의 열린 코드는 새 코드를 낼 때 닫힌다.
 * 코드는 **지금 열려 있는 세션**([sessionId], 호출한 액세스 토큰의 `sid`)에 묶인다 - 닫힌 세션이거나 세션이 없으면 401 로 내지 않고, 코드를 바꿀 때 그 세션이 닫혔어도 거절된다(훔친 15분짜리 액세스 토큰이 새 세션으로 바뀌지 못하게).
 */
fun interface WebHandoffIssuer {
    fun issue(accountId: String, sessionId: String?): String
}

/** 호출한 액세스 토큰의 `sid`(세션 id)를 내는 보안 주체 - `auth` 의 `CurrentPrincipal` 이 구현한다. 세션이 필요한 모듈이 `auth` 를 끌어오지 않고 읽게 하는 자리 */
interface SessionBoundPrincipal {
    val sessionId: String?
}
