package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.account.AccountLabels

/** [AccountLabels] 를 계정 저장소로 — 이메일, 없으면 아이디. 지워진(`ERASED`) 계정은 없는 계정과 같다 */
class RepositoryAccountLabels(private val accounts: () -> AccountRepository) : AccountLabels {
    override fun of(ids: Collection<String>): Map<String, String> {
        val repo = accounts()
        return ids.toSet().mapNotNull { id ->
            val account = repo.findById(id)?.takeIf { it.status != AccountStatus.ERASED } ?: return@mapNotNull null
            (account.email ?: account.loginId)?.let { id to it }
        }.toMap()
    }
}
