package dev.sumin.skeleton.account.events

import java.time.Instant
import org.slf4j.LoggerFactory

enum class AccountEventType {
    SIGN_UP, EMAIL_VERIFIED, LOGIN_SUCCESS, LOGIN_FAILURE, LOGIN_THROTTLED, MAGIC_LINK_REQUESTED,
    PASSWORD_RESET_REQUESTED, PASSWORD_RESET, PASSWORD_CHANGED, EMAIL_CHANGE_REQUESTED, EMAIL_CHANGED,
    SESSION_REVOKED, SESSIONS_REVOKED, REFRESH_REUSE_DETECTED,
    IDENTITY_LINKED, IDENTITY_UNLINKED, ACCOUNT_SUSPENDED, ACCOUNT_UNSUSPENDED, ROLE_GRANTED, ROLE_REVOKED,
    DELETION_SCHEDULED, ACCOUNT_RESTORED, ACCOUNT_PURGED, ADMIN_BOOTSTRAPPED,
    ACCOUNT_ERASED_BY_ADMIN, REGISTRATION_BLOCK_ADDED, REGISTRATION_BLOCK_REMOVED, REGISTRATION_BLOCKED, DELETION_CANCELLED,
}

/**
 * 계정에 일어난 일 하나. [detail] 에는 **토큰 · 비밀번호 · 이메일 원문을 싣지 않는다** (방법 · 이유 같은 짧은 코드, 계정이 없는 실패는 입력 해시 앞자리).
 * [accountId] 가 없을 수 있다 (없는 계정에 대한 로그인 실패).
 */
data class AccountEvent(
    val type: AccountEventType,
    val accountId: String?,
    val at: Instant,
    val ip: String? = null,
    val detail: Map<String, String> = emptyMap(),
)

/** 계정 이벤트를 듣는다 — 빈으로 등록하면 모든 이벤트를 받는다 (감사 표 · 경보 · 지표). 던지는 듣는 쪽은 로그만 남기고 다른 듣는 쪽에 영향이 없다 */
fun interface AccountEventListener {
    fun on(event: AccountEvent)
}

/** 계정 모듈이 모든 인증 사건을 내보내는 한 곳 */
interface AccountEventPublisher {
    fun publish(type: AccountEventType, accountId: String? = null, ip: String? = null, detail: Map<String, String> = emptyMap())

    companion object {
        val NONE: AccountEventPublisher = object : AccountEventPublisher {
            override fun publish(type: AccountEventType, accountId: String?, ip: String?, detail: Map<String, String>) = Unit
        }
    }
}

class DefaultAccountEventPublisher(
    private val now: () -> Instant,
    private val listeners: () -> List<AccountEventListener>,
) : AccountEventPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(type: AccountEventType, accountId: String?, ip: String?, detail: Map<String, String>) {
        val event = AccountEvent(type, accountId, now(), ip, detail)
        listeners().forEach {
            try { it.on(event) } catch (e: Exception) { log.warn("account event listener failed type={}: {}", type, e.javaClass.simpleName) }
        }
    }
}

/** 기본 듣는 쪽 — 한 줄 로그 (계정 id 와 유형만. 이메일 · 토큰 없음) */
class LoggingAccountEventListener : AccountEventListener {
    private val log = LoggerFactory.getLogger("dev.sumin.skeleton.account.events")

    override fun on(event: AccountEvent) {
        log.info("account event type={} account={} ip={} detail={}", event.type, event.accountId ?: "-", event.ip ?: "-", event.detail)
    }
}

/**
 * `auth-session` 의 [SessionEvent] 를 계정 이벤트로 잇는다 — 재사용 탐지가 감사 표 · 주인 경보 · 로그에 닿는 길이다.
 * 세션 모듈이 없으면 이 빈은 아무도 부르지 않는다.
 */
class AccountSessionEventListener(private val events: AccountEventPublisher) : dev.sumin.skeleton.auth.session.SessionEventListener {
    override fun on(event: dev.sumin.skeleton.auth.session.SessionEvent) {
        val detail = buildMap {
            event.sessionId?.let { put("session", it) }
            if (event.count > 0) put("count", event.count.toString())
            event.reason?.let { put("reason", it) }
        }
        val type = when (event.type) {
            dev.sumin.skeleton.auth.session.SessionEventType.REFRESH_REUSE_DETECTED -> AccountEventType.REFRESH_REUSE_DETECTED
            dev.sumin.skeleton.auth.session.SessionEventType.SESSION_REVOKED -> AccountEventType.SESSION_REVOKED
            dev.sumin.skeleton.auth.session.SessionEventType.SESSIONS_REVOKED -> AccountEventType.SESSIONS_REVOKED
        }
        events.publish(type, event.accountId, event.ip, detail)
    }
}
