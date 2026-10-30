package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.account.Account

/**
 * 이 계정에 2단계가 **필수**인가 - 필수면 끌 수 없다(`409 MFA.REQUIRED_BY_POLICY`, 재등록은 된다)이고 상태의 `required` 가 선다.
 * 기본 구현은 설정 `skeleton.auth-mfa.required-for`([ConfiguredMfaPolicy]). 「요금제 · 역할별 필수」 같은 앱 고유 규칙은 이 빈 하나로 갈아 끼운다.
 * 등록 전 계정을 막는 문([dev.sumin.skeleton.auth.mfa.web.EnrollmentRequiredInterceptor])과 15분 면제도 이 정책을 읽는다.
 */
fun interface MfaPolicy {
    fun required(account: Account): Boolean
}

/**
 * NONE 은 아무도, ALL 은 모두. OPERATORS(`OperatorAccess` 권한이 하나라도 있는 계정)의 판정은 [operators] 로 받는다 - 자동설정이 `OperatorAccess` 로 채운다.
 */
class ConfiguredMfaPolicy(
    private val requiredFor: MfaProperties.RequiredFor,
    private val operators: (Account) -> Boolean = { false },
) : MfaPolicy {
    override fun required(account: Account): Boolean = when (requiredFor) {
        MfaProperties.RequiredFor.NONE -> false
        MfaProperties.RequiredFor.ALL -> true
        MfaProperties.RequiredFor.OPERATORS -> operators(account)
    }
}
