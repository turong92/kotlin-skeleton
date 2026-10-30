package dev.sumin.skeleton.workspace

import java.time.Instant

/**
 * 초대 메일 한 통에 필요한 것. [token] 은 평문이라 이 객체와 메일 본문에만 있다 - [toString] 은 주소 · 토큰을 가린다.
 * [locale] 은 초대하는 사람의 요청 언어다.
 */
class InvitationMail(
    val to: String,
    val locale: String?,
    val workspaceName: String,
    val inviterLabel: String?,
    val role: WorkspaceRole,
    val expiresAt: Instant,
    val token: String,
) {
    override fun toString() = "InvitationMail(to=<redacted>, locale=$locale, workspace=$workspaceName, token=<redacted>)"
}

/** 초대 메일을 보내는 한 곳 — 커밋 뒤에 부른다. 실패해도 초대는 남는다(재발송이 있다). 앱이 이 빈을 두면 길을 통째로 바꾼다 */
fun interface WorkspaceInvitationMailer {
    fun send(mail: InvitationMail)
}
