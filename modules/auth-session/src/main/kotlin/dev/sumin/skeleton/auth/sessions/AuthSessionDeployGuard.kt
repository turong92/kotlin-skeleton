package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/** stage · prod 에서 세션이 재시작에 사라지거나 인스턴스끼리 갈라지는 구성, 쿠키가 평문으로 가는 구성을 막는다 */
class AuthSessionDeployGuard(
    private val properties: AuthSessionProperties,
    /** auth 의 보호 프로필 목록(`skeleton.auth.protected-profiles`) — 계정 가드와 같은 환경을 같게 본다 */
    private val protectedProfiles: List<String>,
    /** 저장소는 가드가 돌 때 읽는다 — 빈 생성 순서에 기대지 않는다 */
    private val store: () -> SessionStore?,
) : DeployGuard {
    override val name: String = "auth-session"

    override fun problems(context: DeployContext): List<String> = buildList {
        if (!context.protectedBy(protectedProfiles)) return@buildList
        if (store() is InMemorySessionStore) {
            add("auth-session uses the in-memory SessionStore (sessions vanish on restart and are not shared between instances): add modules:auth-session-jdbc or define your own SessionStore bean")
        }
        if (properties.delivery == AuthSessionProperties.Delivery.COOKIE && !properties.cookie.secure) {
            add("skeleton.auth-session.cookie.secure=false sends the refresh cookie over plain http: remove it (the default is true)")
        }
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (!context.protectedBy(protectedProfiles)) return@buildList
        if (properties.delivery == AuthSessionProperties.Delivery.COOKIE && properties.cookie.sameSite.equals("None", ignoreCase = true)) {
            add("skeleton.auth-session.cookie.same-site=None sends the refresh cookie on cross-site requests: CSRF protection then rests on the ${properties.cookie.csrfHeader} header alone")
        }
    }
}
