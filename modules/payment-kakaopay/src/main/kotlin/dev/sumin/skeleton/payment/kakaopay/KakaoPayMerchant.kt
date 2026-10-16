package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.payment.PaymentMode

/**
 * 카카오페이는 테스트 키와 실결제 키를 키 모양으로 가르지 않는다(Secret key(dev) 와 운영 Secret key 는 둘 다 불투명한 문자열) - 모드는 **가맹점 코드(CID)** 가 정한다.
 * 문서가 밝힌 테스트 CID 는 `TC0ONETIME`(단건) · `TCSUBSCRIP`(정기) · `TCSEQUENCE`(순차) 셋이고, 그 밖의 비어 있지 않은 코드는 실결제로 본다.
 */
internal object KakaoPayMerchant {
    val TEST_CIDS = setOf("TC0ONETIME", "TCSUBSCRIP", "TCSEQUENCE")

    fun modeOf(cid: String?): PaymentMode {
        val code = cid?.trim().orEmpty()
        return when {
            code.isEmpty() -> PaymentMode.UNKNOWN
            code in TEST_CIDS -> PaymentMode.TEST
            else -> PaymentMode.LIVE
        }
    }
}
