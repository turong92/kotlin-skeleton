package dev.sumin.skeleton.common.contact

/**
 * 사용자에게 **서비스 알림**을 메일로 보낼 때 받는 주소를 푸는 곳 — 메일 채널을 만드는 모듈 · 앱이 계정 모듈을 알지 않고 이것만 부른다.
 * `account` 가 구현한다: **연락받을 이메일이 있으면 그것, 없으면 계정 이메일**, 둘 다 없으면 null (보낼 곳이 없다).
 * 계정 · 보안 메일(인증번호 · 비밀번호 재설정 · 매직 링크 …)은 이것을 쓰지 않는다 — 그건 늘 계정 이메일이다.
 */
fun interface ContactAddresses {
    fun contactEmail(accountId: String): String?
}
