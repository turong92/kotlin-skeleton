package dev.sumin.skeleton.common.account

/**
 * 로그인한 계정을 위해 **한 번 쓰는 웹 로그인 코드**를 낸다(앱 -> 웹으로 넘어갈 때). `auth-web-handoff` 가 구현하고, 쓰는 모듈(예: `payment-iap`)은 이 계약만 안다 - 빈이 없으면 그 기능이 없다.
 * 원문은 이 반환값에만 있다 - 호출자가 주소의 `#` 조각에 실어 앱에 돌려주고(조각은 서버로 가지 않는다) 어디에도 남기지 않는다. 같은 계정의 열린 코드는 새 코드를 낼 때 닫힌다.
 */
fun interface WebHandoffIssuer {
    fun issue(accountId: String): String
}
