package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import java.time.Duration

/**
 * 계정 모듈이 `alert` 에 더하는 경보 종류 (`skeleton.alert.intervals.<name>` 으로 간격을 바꾼다). [AlertKind] 는 열린 인터페이스라
 * 이름을 점으로 구분한 문자열로 둔다 (enum 은 `name` 을 못 바꾼다).
 */
object AccountAlertKind {
    val LOGIN_THROTTLED: AlertKind = kind("account.login-throttled", AlertSeverity.WARN, "Sign-in attempts are being throttled (possible brute force)")
    val REFRESH_REUSE: AlertKind = kind("account.refresh-reuse", AlertSeverity.WARN, "A refresh token was replayed (possible token theft)")

    private fun kind(n: String, s: AlertSeverity, t: String) = object : AlertKind {
        override val name = n
        override val severity = s
        override val title = t
        override val minInterval: Duration = Duration.ofMinutes(30)
    }
}

/** 로그인 시도 폭주 · 리프레시 토큰 재사용을 주인 경보로 — 상세에는 이벤트 종류와 범위만 싣는다 (계정 · 이메일 없음). `alert` 모듈이 있을 때만 등록된다 */
class AlertingAccountEventListener(private val alerts: OwnerAlerts) : AccountEventListener {
    override fun on(event: AccountEvent) {
        when (event.type) {
            AccountEventType.LOGIN_THROTTLED ->
                alerts.emit(AccountAlertKind.LOGIN_THROTTLED, key = event.detail["scope"] ?: "login", detail = "throttled scope=${event.detail["scope"]}")
            AccountEventType.REFRESH_REUSE_DETECTED ->
                alerts.emit(AccountAlertKind.REFRESH_REUSE, key = "refresh-reuse", detail = "a rotated-away refresh token was presented again; its session was closed")
            else -> Unit
        }
    }
}
