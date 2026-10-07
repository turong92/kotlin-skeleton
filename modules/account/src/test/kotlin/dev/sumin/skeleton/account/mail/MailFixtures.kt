package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountProperties

/** 골든 파일 · 미리보기가 같이 쓰는 고정 입력 */
internal object MailFixtures {
    val props = AccountProperties.Mail(
        linkBaseUrl = "https://app.example.com",
        brand = AccountProperties.Mail.Brand(serviceName = "Notes", accentColor = "#2563eb", supportAddress = "help@notes.example", footer = "Notes Inc. · Seoul"),
    )
    val vars = mapOf(
        "code" to "739518", "minutes" to "10", "days" to "30", "method" to "Google",
        // "이미 계정이 있어요" 의 기본 — 로그인 페이지 · 가입 수단 · 비밀번호 재설정 **요청** 페이지 (토큰 없음)
        "loginUrl" to "https://app.example.com/login", "methods" to "google,password", "forgotUrl" to "https://app.example.com/forgot-password",
    )

    /** `sign-up.existing-account-mail.include-credentials-links=true` 일 때의 "이미 계정이 있어요" — 재설정 링크 · 일회용 매직 링크가 토큰과 함께 간다 (요청 페이지 줄은 빠진다) */
    val alreadyRegisteredWithLinks = vars - "forgotUrl" + mapOf(
        "resetUrl" to "https://app.example.com/reset-password?token=Zq9-fixedTokenForGoldenFiles_0123456789", "resetMinutes" to "30",
        "magicUrl" to "https://app.example.com/magic-link?token=Zq9-fixedTokenForGoldenFiles_0123456789", "magicMinutes" to "15",
    )
    const val RESET = "https://app.example.com/reset-password?token=Zq9-fixedTokenForGoldenFiles_0123456789"
    const val MAGIC = "https://app.example.com/magic-link?token=Zq9-fixedTokenForGoldenFiles_0123456789"
    fun link(kind: MailKind): String? = when (kind) { MailKind.PASSWORD_RESET -> RESET; MailKind.MAGIC_LINK -> MAGIC; else -> null }
    val langs = listOf("ko", "en")
}
