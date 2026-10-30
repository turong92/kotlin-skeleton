package dev.sumin.skeleton.auth.mfa

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   auth-mfa:
 *     totp:
 *       issuer:                  # 인증 앱에 보이는 서비스 이름. 비면 spring.application.name
 *     enrollment-ttl: 10m        # 등록을 시작하고 첫 코드를 넣을 수 있는 시간
 *     challenge:
 *       ttl: 5m                  # 로그인 대기(mfaToken)의 수명
 *       max-attempts: 5          # 대기 하나에 틀릴 수 있는 횟수 - 다 쓰면 410, 1단계부터 다시
 *     limits:                    # 계정마다 틀린 코드만 센다(맞으면 되돌린다). 걸리면 429 + Retry-After + 창마다 메일 한 통
 *       short: { capacity: 10, window: 15m }
 *       daily: { capacity: 30, window: 24h }
 *     required-for: NONE         # NONE | OPERATORS | ALL - 모듈 기본은 필수 없음. 필수 문(인터셉터)은 다음 조각
 *     http:
 *       enabled: true
 *       base-path: /api/v1/auth/mfa
 * ```
 * TOTP 매개변수(SHA1 · 6자리 · 30초 · ±1 걸음 · 비밀 160비트)와 복구 코드(10개 · 16자)는 **고정**이다 - 인증 앱 호환이 깨지므로 설정으로 열지 않는다.
 */
@ConfigurationProperties("skeleton.auth-mfa")
data class MfaProperties(
    val totp: Totp = Totp(),
    val enrollmentTtl: Duration = Duration.ofMinutes(10),
    val challenge: Challenge = Challenge(),
    val limits: Limits = Limits(),
    val requiredFor: RequiredFor = RequiredFor.NONE,
    val http: Http = Http(),
) {
    init {
        require(!enrollmentTtl.isNegative && !enrollmentTtl.isZero) { "skeleton.auth-mfa.enrollment-ttl must be positive" }
    }

    data class Totp(val issuer: String = "")

    data class Challenge(val ttl: Duration = Duration.ofMinutes(5), val maxAttempts: Int = 5) {
        init {
            require(!ttl.isNegative && !ttl.isZero) { "skeleton.auth-mfa.challenge.ttl must be positive" }
            require(maxAttempts >= 1) { "skeleton.auth-mfa.challenge.max-attempts must be at least 1" }
        }
    }

    data class Limits(
        val short: Window = Window(10, Duration.ofMinutes(15)),
        val daily: Window = Window(30, Duration.ofHours(24)),
    )

    data class Window(val capacity: Int, val window: Duration) {
        init {
            require(capacity >= 1) { "skeleton.auth-mfa.limits.*.capacity must be at least 1" }
            require(!window.isNegative && !window.isZero) { "skeleton.auth-mfa.limits.*.window must be positive" }
        }
    }

    enum class RequiredFor { NONE, OPERATORS, ALL }

    data class Http(
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/auth/mfa",
    )
}
