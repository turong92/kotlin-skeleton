package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef

/**
 * 「이 계정은 결제를 면제받았나」 - 권한 `payment.exempt` ([PaymentPermissions.EXEMPT]) 를 [OperatorAccess] 에 묻는다. 계정 모듈이 있으면 매 호출 저장소의 역할을 읽으므로 회수가 다음 요청부터 먹고,
 * 전권 역할(`*`)은 배정 없이 면제다. 역할 위계가 있으면 물려받은 권한도 따른다(펼친 권한을 읽으므로 따로 할 일이 없다).
 *
 * 앱은 「구매했나 / 구독 중인가」 검사에 `|| exemption.isExempt(id)` 한 줄을 더해 면제 계정을 통과시킨다. 결제 모듈은 면제 계정의 단건 주문을 결제사 없이 완료한다(`provider = "exempt"`).
 * 요청의 `Authentication` 으로 묻는 길은 웹 계층의 확장 함수(`web/PaymentExemptions.kt` 의 `isExempt(authentication)`)다 - 이 클래스는 Spring Security 없이도 뜬다.
 */
class PaymentExemption(private val access: OperatorAccess) {
    /**
     * 계정 id 와, 있으면 요청의 토큰이 싣고 온 역할 이름. 계정 모듈이 있는 조립은 저장된 역할을 읽으므로 [tokenRoles] 가 비어도 된다;
     * 계정 모듈이 없어 토큰의 역할만 아는 조립은 [tokenRoles] 를 넘겨야 한다.
     */
    fun isExempt(accountId: String, tokenRoles: Set<String> = emptySet()): Boolean =
        access.has(OperatorRef(accountId, tokenRoles), PaymentPermissions.EXEMPT)
}
