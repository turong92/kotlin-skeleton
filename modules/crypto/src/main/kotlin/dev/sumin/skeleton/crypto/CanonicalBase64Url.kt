package dev.sumin.skeleton.crypto

import java.util.Base64

/**
 * base64url 을 정규형(패딩 없음 · 마지막 글자의 버려지는 비트가 0)으로만 받는다.
 * JDK 디코더는 마지막 글자의 하위 비트를 검사하지 않아, 서로 다른 문자열이 같은 바이트로 디코드된다(토큰 가변성) —
 * 불투명 URL 토큰은 문자열 하나가 값 하나여야 캐시 키 · 차단 목록 · 로그 대조가 맞으므로 다시 인코드해 같은지 확인한다.
 */
internal object CanonicalBase64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun decode(text: String): ByteArray {
        val bytes = Base64.getUrlDecoder().decode(text)
        require(encoder.encodeToString(bytes) == text) { "base64url text is not in canonical form." }
        return bytes
    }
}
