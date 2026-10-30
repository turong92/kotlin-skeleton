package dev.sumin.skeleton.auth.mfa

import io.nayuki.qrcodegen.QrCode

/**
 * QR 그림 — [path] 는 `<svg viewBox="0 0 size size"><path d=…/></svg>` 에 그대로 넣는 **경로 문자열 하나**다(어두운 칸을 가로 줄로 묶었다. 사방 4칸 여백 포함).
 * 서버가 만들어 내려 주므로 프런트에 QR 라이브러리가 없고, `innerHTML` · data URI 없이 `<path d>` 로 그린다. `otpauth://` 는 어디로도 나가지 않는다.
 */
data class QrImage(val size: Int, val path: String)

object QrSvg {
    private const val QUIET_ZONE = 4

    fun of(text: String): QrImage {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val path = StringBuilder()
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                if (!qr.getModule(x, y)) { x++; continue }
                val start = x
                while (x < qr.size && qr.getModule(x, y)) x++
                path.append('M').append(start + QUIET_ZONE).append(' ').append(y + QUIET_ZONE).append('h').append(x - start).append("v1h-").append(x - start).append('z')
            }
        }
        return QrImage(qr.size + 2 * QUIET_ZONE, path.toString())
    }
}
