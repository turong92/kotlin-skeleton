package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.Emails
import dev.sumin.skeleton.account.SignInMethods

/** 비밀번호 수단 — 주체는 이메일이고, 해시는 [dev.sumin.skeleton.account.Identity.secret] 에 있다 */
class PasswordSignInMethod : SignInMethod {
    override val code: String = SignInMethods.PASSWORD
    override fun normalize(subject: String): String = Emails.normalize(subject)
    override val exposesSubject: Boolean = true
}
