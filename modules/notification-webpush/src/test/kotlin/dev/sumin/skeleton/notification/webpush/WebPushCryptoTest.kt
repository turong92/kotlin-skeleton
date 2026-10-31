package dev.sumin.skeleton.notification.webpush

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat

/** RFC 8291 부록 A(= 5절 예시) 벡터 — 암호화가 표준과 한 바이트도 다르지 않다는 증거. 이 값이 틀리면 브라우저가 본문을 풀지 못한다 */
class WebPushCryptoTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun d(s: String) = Base64.getUrlDecoder().decode(s.replace(Regex("\\s"), ""))

    private val asPrivate = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
    private val asPublic = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val authSecret = "BTBZMqHH6r4Tts7J_aSIgg"
    private val salt = "DGv6ra1nlYgDCS1FRnbzlw"
    private val plaintext = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24"
    private val header = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val cipher = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ"

    @Test
    fun `RFC 8291 Appendix A vector encrypts byte for byte`() {
        val body = WebPushCrypto.encrypt(
            plaintext = d(plaintext),
            uaPublic = d(uaPublic),
            authSecret = d(authSecret),
            serverKey = WebPushCrypto.ServerKey(WebPushKeys.privateKey(d(asPrivate)), d(asPublic)),
            salt = d(salt),
        )
        assertEquals(b64.encodeToString(d(header) + d(cipher)), b64.encodeToString(body))   // 86바이트 머리는 3의 배수가 아니라 글자째로는 못 붙인다 - 바이트로 붙인다
    }

    @Test
    fun `every call uses a fresh salt and ephemeral key`() {
        val body = WebPushCrypto.encrypt("hello".toByteArray(), d(uaPublic), d(authSecret))
        // 머리: salt 16 + rs 4 + idlen 1 + keyid 65 = 86 — 매번 새 salt · 새 임시 키
        assertThat(body[20].toInt()).isEqualTo(65)
        val again = WebPushCrypto.encrypt("hello".toByteArray(), d(uaPublic), d(authSecret))
        assertThat(body).isNotEqualTo(again)
    }
}
