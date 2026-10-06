package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.auth.session.SessionEventType
import org.slf4j.LoggerFactory

/** 등록된 [SessionEventListener] 모두에게 알린다 — 하나가 던져도 나머지에 영향이 없고 세션 동작도 막지 않는다 */
class SessionEventDispatch(private val listeners: () -> List<SessionEventListener>) : (SessionEvent) -> Unit {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun invoke(event: SessionEvent) {
        listeners().forEach {
            try { it.on(event) } catch (e: Exception) { log.warn("session event listener failed type={}: {}", event.type, e.javaClass.simpleName) }
        }
    }
}

class LoggingSessionEventListener : SessionEventListener {
    private val log = LoggerFactory.getLogger("dev.sumin.skeleton.auth.sessions.events")

    override fun on(event: SessionEvent) {
        when (event.type) {
            SessionEventType.REFRESH_REUSE_DETECTED ->
                log.warn("refresh token reuse detected session={} account={} ip={}: a rotated-away token was presented again and the session was closed", event.sessionId, event.accountId, event.ip ?: "-")
            SessionEventType.SESSION_REVOKED -> log.info("session revoked session={} account={}", event.sessionId, event.accountId)
            SessionEventType.SESSIONS_REVOKED -> log.info("sessions revoked count={} account={} reason={}", event.count, event.accountId, event.reason ?: "-")
        }
    }
}
