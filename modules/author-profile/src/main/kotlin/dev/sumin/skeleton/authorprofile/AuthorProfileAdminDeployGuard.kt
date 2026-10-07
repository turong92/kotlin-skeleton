package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.LoggingAccountEventListener
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * `skeleton.author-profile.admin.enabled=true` 인데 로그 한 줄 말고는 계정 이벤트를 받는 쪽이 없으면(= `AUTHOR_LINK_VIEWED` 가 어디에도 남지 않는다) stage · prod 에서 **경고**한다.
 * 문제(기동 실패)가 아닌 이유: `AccountDeployGuard` 가 비슷한 운영 전제(로그인 제한 · 관리자 부트스트랩)를 경고로 두는 것과 같다 — 감사를 직접 처리하는 앱의 리스너도 있을 수 있어 막지 않는다.
 * 기본 로그 리스너([LoggingAccountEventListener]) 밖의 리스너가 하나라도 있으면 "남는 길이 있다"고 본다(`account-jdbc` 의 `skeleton.account.audit.enabled=true` 또는 앱의 리스너).
 */
class AuthorProfileAdminDeployGuard(private val listeners: () -> List<AccountEventListener>) : DeployGuard {
    override val name: String = "author-profile"

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (!context.protectedEnv) return@buildList
        if (listeners().all { it is LoggingAccountEventListener }) {
            add(
                "skeleton.author-profile.admin.enabled=true but no account event listener besides the log line exists, so AUTHOR_LINK_VIEWED (who looked at which account's profiles) is not kept anywhere durable: " +
                    "set skeleton.account.audit.enabled=true with modules:account-jdbc, or define your own AccountEventListener",
            )
        }
    }
}
