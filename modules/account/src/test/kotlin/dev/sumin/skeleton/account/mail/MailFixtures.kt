package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountProperties

/** 골든 파일 · 미리보기가 같이 쓰는 고정 입력 */
internal object MailFixtures {
    val props = AccountProperties.Mail(
        linkBaseUrl = "https://app.example.com",
        brand = AccountProperties.Mail.Brand(serviceName = "Notes", accentColor = "#2563eb", supportAddress = "help@notes.example", footer = "Notes Inc. · Seoul"),
    )
    val vars = mapOf("code" to "739518", "minutes" to "10", "days" to "30", "method" to "Google")
    const val RESET = "https://app.example.com/reset-password?token=Zq9-fixedTokenForGoldenFiles_0123456789"
    const val MAGIC = "https://app.example.com/magic-link?token=Zq9-fixedTokenForGoldenFiles_0123456789"
    fun link(kind: MailKind): String? = when (kind) { MailKind.PASSWORD_RESET -> RESET; MailKind.MAGIC_LINK -> MAGIC; else -> null }
    val langs = listOf("ko", "en")
}
