package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.NotificationEvent
import java.time.Instant

/**
 * 푸시를 실제로 보내는 곳 — 종류([kind])마다 빈 하나(웹 푸시 · FCM, 새 전달 수단이 같은 자리에 끼운다).
 * [send] 는 판정만 돌려준다: 재시도 · 지우기 · 죽은 작업 규칙은 처리기가 한 곳에서 정한다. 던지면 큐의 재시도에 맡겨진다.
 */
interface PushSender {
    val kind: String

    /** 화면이 구독할 때 쓰는 서버 공개 키(웹 푸시의 VAPID 공개 키). 없으면 null — `GET /config` 와 기기 줄의 키 지문이 이것을 쓴다 */
    val publicKey: String? get() = null

    /** 종류별 등록 검사(끝점 허용 목록 · 열쇠 길이 · 토큰 길이). 거절 이유 한 줄(비밀 없이)을 돌려주면 400 `PUSH.INVALID_DEVICE`, 괜찮으면 null */
    fun validate(registration: PushRegistration): String? = null

    fun send(device: PushDevice, message: PushMessage): PushResult
}

/** 알림 → 푸시 내용. null 이면 이 알림은 푸시하지 않는다. 번역 · 문구는 앱 몫이다 — 기본 구현은 `lock-screen` 설정을 따른다 */
fun interface PushMessageFactory {
    fun create(event: NotificationEvent): PushMessage?
}

/** 기기 저장소. 구현은 `notification-push-jdbc`. 인메모리 구현은 없다(재시작하면 구독이 사라져 쓸모없다) */
interface PushDeviceStore {
    /** 등록(=갱신). 같은 주소를 다른 설치가 쓰고 있으면 그 옛 줄을 지우고, 계정 기기가 상한을 넘으면 `last_seen_at` 이 가장 오랜 것부터 지운다 */
    fun register(request: PushDeviceRegistration): PushDevice

    fun find(id: String): PushDevice?

    /** 최근에 본 순 */
    fun listByAccount(accountId: String): List<PushDevice>

    fun listByAccounts(accountIds: Set<String>): List<PushDevice>

    fun findByInstallation(accountId: String, installationId: String): PushDevice?

    /** **내 것만** 지운다 — 없거나 남의 것이면 아무것도 안 지우고 false */
    fun deleteByInstallation(accountId: String, installationId: String): Boolean

    fun delete(id: String): Boolean

    fun deleteBySession(accountId: String, sessionId: String): Int

    fun deleteByAccount(accountId: String): Int

    /** `last_seen_at < before` 인 줄을 지운다. 지운 수 */
    fun deleteStale(before: Instant): Int

    fun markSuccess(id: String, at: Instant)
}

/** 「푸시만 끄기」 — 종류 × 채널. 끈 것만 기억한다(기존 「모든 채널 끄기」 `NotificationMuteRepository` 와 별개) */
interface PushTypeMuteStore {
    fun mutedTypes(accountId: String): Set<String>

    /** [accountIds] 중 [type] 의 푸시를 끈 사람 */
    fun mutedAmong(type: String, accountIds: Set<String>): Set<String>

    fun setMuted(accountId: String, type: String, muted: Boolean)
}

/** 발송 작업을 큐에 넣는다 — 구현은 `notification-push-jdbc`(`job-queue-jdbc`). [runAt] 이 있으면 그때 이후에 집는다(보류) */
interface PushTasks {
    fun enqueue(job: PushJob, runAt: Instant? = null)
}

/** 호출자의 로그인 세션 id(`sid`) — `auth` 가 있을 때만 값이 있다. 기기 줄에 적어 두었다가 세션이 끊기면 같이 지운다 */
fun interface PushSessionIdResolver {
    fun sessionIdOf(principal: Any?): String?
}

/** 일꾼이 「잠시 후 다시」로 던진다 — 큐가 백오프로 재시도한다 */
class PushRetryException(val after: java.time.Duration?) : RuntimeException("push delivery should be retried")

/** 일꾼이 「다시 해도 안 된다」로 던진다 — 큐가 재시도 없이 죽은 작업으로 보낸다 */
class PushPermanentFailure(message: String) : RuntimeException(message)
