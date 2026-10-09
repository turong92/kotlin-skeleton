package dev.sumin.skeleton.payment

/**
 * 결제사가 준 값이 줄에 들어가기 전의 마지막 걸름(스펙 §5.1) — **식별자 · 코드 모양이 아니면 버린다.** 자유 문장이 열에 들어갈 길이 없다.
 * 결제사 모듈이 실수해도 장부가 개인정보를 담지 않게 하는 둘째 방어다(첫째는 모듈의 허용 목록).
 */
internal object LedgerValues {
    private val CODE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val ISSUER = Regex("[A-Za-z0-9_-]{1,40}")
    private val METHOD = Regex("[A-Z][A-Z0-9_]{0,39}")
    private val LAST4 = Regex("[0-9*]{1,4}")

    /** 결제사의 거절 · 상태 코드. */
    fun code(value: String?): String? = value?.trim()?.takeIf { CODE.matches(it) }

    fun cardIssuer(value: String?): String? = value?.trim()?.takeIf { ISSUER.matches(it) }

    /** 마지막 네 글자 — 숫자 또는 `*` 가 아니면 버린다. */
    fun cardLast4(value: String?): String? = value?.takeLast(4)?.takeIf { LAST4.matches(it) }

    /**
     * 결제 수단 **종류 코드**(`CARD` · `EASY_PAY` …). 라벨 · 사람 이름 모양은 코드가 아니므로 버린다 — 결제사 모듈이 자기 값을 이 코드로 옮겨 보낸다
     * (토스는 `payment-toss` 의 대응표). 영문으로 시작해야 한다 — 숫자만인 값(전화번호 · 카드 번호)은 코드가 아니다. 대응이 없어 null 이 된 줄은 "수단을 모른다"일 뿐 결제의 사실은 그대로다.
     */
    fun method(value: String?): String? = value?.trim()?.takeIf { METHOD.matches(it) }
}
