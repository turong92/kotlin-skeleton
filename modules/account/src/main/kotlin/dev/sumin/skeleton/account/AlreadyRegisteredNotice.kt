package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind

// "이미 계정이 있어요" 메일 — 가입([RegistrationService])과 첫 이메일 등록([EmailChangeService])이 같은 메일 · 같은 규칙을 쓴다.

internal fun AccountCore.notifyAlreadyRegistered(existing: Account, allowed: Boolean, credentialLinks: Boolean) {
    val address = existing.email ?: return
    if (existing.status == AccountStatus.DELETED || !allowed) return
    mailer.send(AccountMail(MailKind.ALREADY_REGISTERED, address, existing.locale, vars = alreadyRegisteredVars(existing, credentialLinks)))
}

/**
 * "이미 계정이 있어요" 메일의 다음 걸음: 로그인 페이지 · 가입 수단(코드 목록 — 문구는 템플릿이 로케일로) · "비밀번호를 잊었다면" 재설정 **요청 페이지** — 모두 **토큰 없는 링크**다.
 * 이 메일은 **남이 넣은 요청**이 만든다 — 그래서 토큰을 만들지 않는 것이 기본이다 (주인이 방금 받은 재설정 링크를 닫지 못하고, 재설정 · 매직 링크 한도를 못 바닥낸다).
 * `sign-up.existing-account-mail.include-credentials-links=true` 이고 [credentialLinks](최초 가입 요청; 재전송은 false)이면 재설정 링크 · (`auth-magic-link` 가 있으면) 매직 링크도 싣되
 * **열린 토큰이 없을 때만** 새로 만든다. 정지된 계정에는 링크를 주지 않는다(박제). 뒤로 넘긴 일 안에서만 돈다 — 화면 응답은 계정이 있든 없든 같다.
 */
internal fun AccountCore.alreadyRegisteredVars(existing: Account, credentialLinks: Boolean): Map<String, String> = buildMap {
    links.login()?.let { put("loginUrl", it) }
    accounts.identitiesOf(existing.id).map { it.method }.distinct().takeIf { it.isNotEmpty() }?.let { put("methods", it.joinToString(",")) }
    if (existing.status == AccountStatus.SUSPENDED) return@buildMap
    if (credentialLinks && props.signUp.existingAccountMail.includeCredentialsLinks) {
        issueResetLink(existing, onlyIfNoneOpen = true)?.let {
            put("resetUrl", it.url); put("resetMinutes", it.minutes.toString())
            events.publish(AccountEventType.PASSWORD_RESET_REQUESTED, existing.id, detail = mapOf("via" to "already_registered"))
        }
        magicLinks()?.issue(existing)?.let { put("magicUrl", it.url); put("magicMinutes", it.minutes.toString()) }
    }
    if ("resetUrl" !in this) links.forgot()?.let { put("forgotUrl", it) }
}
