package dev.sumin.skeleton.workspace

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 초대 토큰 — 무작위 32바이트 base64url. 저장은 sha256 hex 만이다(`auth-session` 의 리프레시 토큰과 같다) */
internal object InvitationTokens {
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
