package dev.sumin.skeleton.alert

import java.time.Duration
import java.time.Instant

enum class AlertSeverity { INFO, WARN, ERROR, CRITICAL }

/**
 * 주인에게 알릴 일의 종류. **닫힌 enum 이 아니다** — 앱이 자기 enum · object 로 구현해 `OwnerAlerts.emit` 에 넘긴다.
 * [name] 은 표 · 설정 · 로그에서 쓰는 안정된 이름(`skeleton.alert.intervals.<name>`), [minInterval] 은 같은 (종류 · 키)가 다시 와도
 * 내보내지 않고 접는 최소 간격(0 이면 늘 내보낸다 — 키가 이미 건마다 다른 결제 · 주문 같은 것).
 * [directFallback] 이 true 면 기록(DB)이 막혀도 웹훅으로 직접 한 번 보낸다 — DB 가 죽은 것과 함께 오는 경보용.
 */
interface AlertKind {
    val name: String
    val severity: AlertSeverity
    val title: String
    val minInterval: Duration get() = Duration.ZERO
    val directFallback: Boolean get() = false
}

/** 모듈이 스스로 내는 종류. 앱 종류는 [AlertKind] 를 직접 구현한다 */
enum class BuiltInAlertKind(
    override val severity: AlertSeverity,
    override val title: String,
    override val minInterval: Duration,
    override val directFallback: Boolean = false,
) : AlertKind {
    SERVER_ERROR_SURGE(AlertSeverity.CRITICAL, "Server errors (5xx) are piling up", Duration.ofMinutes(15), directFallback = true),
    STARTUP_FAILED(AlertSeverity.CRITICAL, "The server failed to start", Duration.ZERO),
    JOB_DEAD(AlertSeverity.ERROR, "A background job ran out of attempts (DEAD)", Duration.ofMinutes(30)),
    TEST(AlertSeverity.INFO, "Test alert", Duration.ofMinutes(1)),
}

/** 채널로 나가는 글 전부. 웹훅 주소 · 받는 주소는 설정에서 읽는다 — 여기 없다 */
data class AlertMessage(
    val kind: String,
    val key: String,
    val severity: AlertSeverity,
    val title: String,
    val detail: String,
    /** 이 알림 앞에 접어 둔 같은 (종류 · 키) 수 */
    val suppressed: Int,
    val occurredAt: Instant,
    val environment: String,
)

/** [AlertStore.record] 의 결과 — [send] 면 이번에 내보내고, [suppressedFolded] 는 이 알림에 「N건 더」로 싣는 수 */
data class AlertRecorded(val send: Boolean, val suppressedFolded: Int, val occurrences: Long)

/** 보내기 실패 — **주소 · 토큰을 싣지 않는다**(웹훅 주소는 경로에 비밀이 있다). 로그에 이 메시지가 남는다 */
class AlertDeliveryException(message: String) : RuntimeException(message)
