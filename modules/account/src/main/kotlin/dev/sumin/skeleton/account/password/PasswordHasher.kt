package dev.sumin.skeleton.account.password

import dev.sumin.skeleton.account.AccountProperties
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 비밀번호 해시. 새 해시는 `{알고리즘}` 접두사를 달아 저장한다 — 알고리즘을 나중에 바꿔도 옛 해시가 그대로 검증되고,
 * 로그인에 성공했을 때 [needsUpgrade] 가 참이면 새 방식으로 다시 해시해 저장한다 (업그레이드는 사용자 몰래 로그인에 얹힌다).
 * 어떤 입력에도 던지지 않는다 — 형식이 이상한 해시 · 너무 긴 비밀번호는 그냥 "일치하지 않음".
 */
class PasswordHasher(private val encoder: PasswordEncoder) {
    fun hash(raw: String): String = requireNotNull(encoder.encode(raw)) { "password encoder returned null" }

    fun matches(raw: String, hash: String): Boolean {
        if (hash.isBlank()) return false
        return try { encoder.matches(raw, hash) } catch (_: IllegalArgumentException) { false }
    }

    fun needsUpgrade(hash: String): Boolean = try { encoder.upgradeEncoding(hash) } catch (_: IllegalArgumentException) { false }
}

object PasswordEncoders {
    /**
     * 기본 인코더: 새 해시는 설정한 알고리즘(bcrypt · argon2), 검증은 `{bcrypt}` `{argon2}` 접두사 해시와 **접두사 없는 bcrypt**(auth 모듈이 지금까지 저장하던 형태)를 모두 받는다.
     * bcrypt 를 고른 이유: 추가 의존이 없고 · OWASP 가 허용하는 설정(cost ≥ 10)이며 · 해시 한 번이 메모리를 쓰지 않아 로그인 폭주 때 메모리 DoS 면이 작다.
     * argon2id 가 더 강한 선택이라 opt-in 으로 열어 둔다(BouncyCastle `bcprov-jdk18on` 이 클래스패스에 있어야 한다).
     */
    fun delegating(props: AccountProperties.Password): PasswordEncoder {
        val bcrypt = BCryptPasswordEncoder(props.bcryptStrength)
        val encoders = mutableMapOf<String, PasswordEncoder>("bcrypt" to bcrypt)
        if (props.encoder == "argon2" || argon2Available()) argon2(props.encoder == "argon2")?.let { encoders["argon2"] = it }
        check(props.encoder in encoders) { "unreachable" }
        return DelegatingPasswordEncoder(props.encoder, encoders).apply { setDefaultPasswordEncoderForMatches(bcrypt) }
    }

    private fun argon2Available(): Boolean = runCatching { Class.forName("org.bouncycastle.crypto.generators.Argon2BytesGenerator") }.isSuccess

    private fun argon2(required: Boolean): PasswordEncoder? {
        if (!argon2Available()) {
            check(!required) { "skeleton.account.password.encoder=argon2 needs BouncyCastle on the classpath: add runtimeOnly(\"org.bouncycastle:bcprov-jdk18on\")" }
            return null
        }
        return org.springframework.security.crypto.argon2.Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    }
}
