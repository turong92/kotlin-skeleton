package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/**
 * 거래 번호(TID) · 주문 번호 · 승인 토큰의 모양을 결제사를 부르기 **전에** 확인한다. 이 값들은 요청 본문에 들어가고(경로에는 안 들어간다) 승인 토큰은 구매자 브라우저가 가져온 값이다.
 * 문서의 TID 는 20자(`T1234567890123456789`)인데 문자 집합은 밝히지 않았다 - 영문 · 숫자 · `-` · `_` 로 넓게 받는다(**못 잰 가정**, `docs/real-provider-setup.md`).
 */
internal object KakaoPayIdentifiers {
    private val TID = Regex("[A-Za-z0-9_-]{1,50}")
    private val ORDER_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val TOKEN = Regex("[A-Za-z0-9_.~-]{1,200}")

    /** 카카오페이가 「가맹점 회원 id」로 받는 값 - 최대 100자, 개인정보 불가. 우리 계정 id 는 불투명한 식별자다. */
    private val USER = Regex("[A-Za-z0-9_.:-]{1,100}")

    fun isTid(value: String): Boolean = TID.matches(value)

    fun isApprovalToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    fun tid(value: String): String = if (isTid(value)) value else throw KakaoPayIdentifierRejectedException()

    fun orderId(value: String): String = if (ORDER_ID.matches(value)) value else throw KakaoPayIdentifierRejectedException()

    fun approvalToken(value: String?): String = value?.takeIf { TOKEN.matches(it) } ?: throw KakaoPayIdentifierRejectedException()

    /** 계정 id 가 없거나 이 모양이 아니면(이메일 같은 개인정보일 수 있다) 주문 번호로 대신한다 - `ready` 와 `approve` 가 같은 값을 보내야 한다. */
    fun partnerUserId(accountId: String?, orderId: String): String = accountId?.takeIf { USER.matches(it) && '@' !in it } ?: orderId
}

/** 거래 번호 · 승인 토큰 · 주문 번호가 카카오페이의 모양이 아니어서 결제사를 부르지 않았다 - 호출자 잘못(400)이다. 문장은 고정이다(거절한 값을 싣지 않는다). */
class KakaoPayIdentifierRejectedException : ApplicationException(
    message = "The payment identifier is not in a valid form.",
    errorCode = PlatformErrorCode.VALIDATION_FAILED,
)
