package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.account.AccountFacts
import java.time.Instant

/** [AccountFacts] 를 계정 저장소로 — 지워진(`ERASED`) 계정은 없는 계정과 같다 */
class RepositoryAccountFacts(private val accounts: () -> AccountRepository) : AccountFacts {
    override fun createdAt(accountId: String): Instant? =
        accounts().findById(accountId)?.takeIf { it.status != AccountStatus.ERASED }?.createdAt

    override fun verifiedEmail(accountId: String): String? =
        accounts().findById(accountId)?.takeIf { it.status != AccountStatus.ERASED && it.emailVerified }?.email
}
