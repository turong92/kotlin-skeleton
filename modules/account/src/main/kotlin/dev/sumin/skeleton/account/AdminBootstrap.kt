package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 첫 관리자 만들기. `skeleton.account.bootstrap.admin-email` 로 정한 이메일의 계정이 **이메일이 확인된 뒤**(인증 링크 · 매직 링크 · 확인된 소셜 ·
 * 비밀번호 재설정으로 메일함을 증명한 시점) 로그인할 수 있게 됐고 ADMIN 역할을 가진 활성 계정이 아직 없으면 ADMIN 을 준다.
 * 비밀번호 기본값 · 숨은 계정은 없다 — 그 이메일을 가진 사람이 정상 가입 흐름으로 들어와야 한다. 미확인 가입으로는 절대 승격되지 않는다.
 */
class AdminBootstrap(
    private val props: AccountProperties.Bootstrap,
    private val adminRole: String,
    private val accounts: () -> AccountRepository,
    private val events: () -> AccountEventPublisher,
    private val time: TimeProvider,
) {
    fun afterVerified(account: Account) {
        if (props.adminEmail.isBlank()) return
        if (!account.emailVerified || account.status != AccountStatus.ACTIVE) return
        if (account.email != Emails.normalize(props.adminEmail)) return
        val repo = accounts()
        if (repo.countActiveWithRole(adminRole) > 0) return
        if (repo.grantRole(account.id, adminRole, time.now())) {
            events().publish(AccountEventType.ADMIN_BOOTSTRAPPED, account.id)
        }
    }
}
