package dev.sumin.skeleton.auth.login

import dev.sumin.skeleton.auth.account.AuthAccount

/**
 * 비밀번호 로그인 시도 하나. [identifier] 는 정규화된 입력(`email:a@b.c` · `username:x` · `accountId:y`) — 계정이 있든 없든 같은 모양이라
 * 한도 키로 써도 계정 존재 여부가 드러나지 않는다.
 */
data class LoginAttempt(val identifier: String, val clientIp: String?)

enum class LoginFailure { UNKNOWN_ACCOUNT, BAD_PASSWORD, BLOCKED }

/**
 * 로그인 앞뒤에 끼는 고리 — 빈으로 등록하면 [PasswordLoginService] 가 모아 부른다 (`account` 가 한도 · 이벤트를 여기에 건다).
 * [beforeAttempt] 가 던지면 비밀번호 검사 없이 거기서 끝난다 (예: 429). 나머지는 알림용이라 던지지 않는다.
 */
interface LoginHooks {
    fun beforeAttempt(attempt: LoginAttempt) {}

    /** [account] 는 계정을 찾았을 때만(잘못된 비밀번호 · 막힌 계정) — 없는 계정이면 null */
    fun onFailure(attempt: LoginAttempt, reason: LoginFailure, account: AuthAccount?) {}

    fun onSuccess(attempt: LoginAttempt, account: AuthAccount) {}
}
