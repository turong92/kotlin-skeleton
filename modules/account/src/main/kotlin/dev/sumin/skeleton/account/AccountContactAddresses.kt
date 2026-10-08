package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.contact.ContactAddresses

/**
 * 서비스 알림의 받는 주소: 연락 이메일(기능이 켜져 있고 확인된 것 — 저장되는 것은 코드를 입력한 주소뿐이다)이 있으면 그것, 없으면 **확인된** 계정 이메일. 계정 · 보안 메일은 이것을 쓰지 않는다.
 * **ACTIVE 계정만** 받는다 — 정지 · 삭제 중 · 지워진 계정, 이메일이 아직 미확인인 계정에는 주소를 주지 않는다 (정지된 사람의 주소로 서비스 메일을 보내지 않게).
 */
class AccountContactAddresses(private val core: AccountCore) : ContactAddresses {
    override fun contactEmail(accountId: String): String? {
        val account = core.accounts.findById(accountId)?.takeIf { it.status == AccountStatus.ACTIVE } ?: return null
        val contact = if (core.props.contactEmail.enabled) account.contactEmail else null
        return contact ?: account.email?.takeIf { account.emailVerified }
    }
}
