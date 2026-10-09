package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.PaymentMode

/**
 * 토스 키의 모양: `<모드>_<종류>_<나머지>`. 모드 `test` · `live`, 종류 `ck`(결제창 클라이언트) · `sk`(결제창 시크릿)
 * · `gck` · `gsk`(결제위젯 묶음 — 이 모듈은 쓰지 않는다). 나머지는 읽지 않는다.
 */
internal data class TossApiKey(val mode: PaymentMode, val kind: Kind) {
    enum class Kind { CLIENT, SECRET, WIDGET_CLIENT, WIDGET_SECRET }

    companion object {
        private val PATTERN = Regex("^(test|live)_(gck|gsk|ck|sk)_.+$")

        /** 모양이 맞지 않으면 null. */
        fun parse(raw: String?): TossApiKey? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val mode = if (match.groupValues[1] == "test") PaymentMode.TEST else PaymentMode.LIVE
            val kind = when (match.groupValues[2]) {
                "ck" -> Kind.CLIENT
                "sk" -> Kind.SECRET
                "gck" -> Kind.WIDGET_CLIENT
                else -> Kind.WIDGET_SECRET
            }
            return TossApiKey(mode, kind)
        }
    }
}
