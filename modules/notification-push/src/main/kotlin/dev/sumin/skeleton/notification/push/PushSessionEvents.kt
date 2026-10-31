package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.auth.session.SessionEventType

/**
 * 로그아웃한 기기는 푸시도 끊긴다. `SESSION_REVOKED` → 그 세션이 등록한 기기, `SESSIONS_REVOKED`(모든 기기 로그아웃) · `REFRESH_REUSE_DETECTED`(토큰 도난 의심) → 그 계정 기기 전부
 * (아직 로그인된 기기는 다음에 화면이 열릴 때 다시 등록한다). `auth` 가 클래스패스에 있을 때만 [PushSessionAutoConfiguration] 이 등록한다.
 */
class PushSessionEventListener(private val devices: PushDeviceStore) : SessionEventListener {
    override fun on(event: SessionEvent) {
        when (event.type) {
            SessionEventType.SESSION_REVOKED -> event.sessionId?.let { devices.deleteBySession(event.accountId, it) }
            SessionEventType.SESSIONS_REVOKED, SessionEventType.REFRESH_REUSE_DETECTED -> devices.deleteByAccount(event.accountId)
        }
    }
}

/** 호출자의 토큰(`CurrentPrincipal`)이 실은 세션 id */
class AuthPushSessionIdResolver : PushSessionIdResolver {
    override fun sessionIdOf(principal: Any?): String? = (principal as? CurrentPrincipal)?.sessionId
}
