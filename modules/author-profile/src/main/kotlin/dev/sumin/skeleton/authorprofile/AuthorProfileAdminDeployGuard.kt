package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.LoggingAccountEventListener
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * `skeleton.author-profile.admin.enabled=true` 인데 로그 한 줄 말고는 계정 이벤트를 받는 쪽이 없으면(= `AUTHOR_LINK_VIEWED` 가 어디에도 남지 않는다) stage · prod 에서 **경고**한다.
 * 문제(기동 실패)가 아닌 이유: `AccountDeployGuard` 가 비슷한 운영 전제(로그인 제한 · 관리자 부트스트랩)를 경고로 두는 것과 같다 — 감사를 직접 처리하는 앱의 리스너도 있을 수 있어 막지 않는다.
 * 기본 로그 리스너([LoggingAccountEventListener])와 경보 리스너(`AlertingAccountEventListener` — `alert` 모듈이 있으면 늘 등록되고 `AUTHOR_LINK_VIEWED` 를 버린다)는 **남기지 않는 쪽**이다.
 * 그 밖의 리스너가 하나라도 있으면 "남는 길이 있다"고 본다(`account-jdbc` 의 `skeleton.account.audit.enabled=true` 또는 앱의 리스너).
 */
class AuthorProfileAdminDeployGuard(private val listeners: () -> List<AccountEventListener>) : DeployGuard {
    override val name: String = "author-profile"

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (!context.protectedEnv) return@buildList
        if (listeners().all { it is LoggingAccountEventListener || it.javaClass.name == ALERTING_LISTENER }) {
            add(
                "skeleton.author-profile.admin.enabled=true but no account event listener besides the log line (and the owner-alert listener, which drops it) exists, so AUTHOR_LINK_VIEWED (who looked at which account's profiles) is not kept anywhere durable: " +
                    "set skeleton.account.audit.enabled=true with modules:account-jdbc, or define your own AccountEventListener",
            )
        }
    }

    private companion object {
        /** 이름으로 비교한다 — `alert` 는 `account` 의 컴파일 전용 의존이라 클래스패스에 없을 수 있고, 있을 때만 이 리스너가 등록된다 */
        const val ALERTING_LISTENER = "dev.sumin.skeleton.account.abuse.AlertingAccountEventListener"
    }
}
