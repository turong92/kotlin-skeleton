package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.contact.ContactAddresses

/** 서비스 알림의 받는 주소: 연락 이메일이 있으면 그것, 없으면 계정 이메일. 계정 · 보안 메일은 이것을 쓰지 않는다 */
class AccountContactAddresses(private val core: AccountCore) : ContactAddresses {
    override fun contactEmail(accountId: String): String? =
        core.accounts.findById(accountId)?.takeIf { it.status != AccountStatus.ERASED }?.let { if (core.props.contactEmail.enabled) it.contactEmail ?: it.email else it.email }
}
