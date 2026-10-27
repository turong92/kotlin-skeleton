package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * 계정별 일별 활동 기록 — 하루에 한 줄 `(계정 id, 날짜)`. 개인 값은 계정 id 뿐이고(IP · 기기 · 시각 없음) 계정이 지워질 때 같이 지운다.
 * 대시보드의 「활성 이용자」(기간 안 서로 다른 계정 수)와 「돌아옴」 단계가 이 기록을 읽는다. 기본 구현은 아무것도 하지 않는다(`auth-session-jdbc` 가 표로 채운다).
 */
interface AccountActivityStore {
    /** 이 계정이 [day] 에 활동했다 — 같은 날 여러 번이어도 한 줄(멱등) */
    fun recordActive(accountId: String, day: LocalDate)

    /** 계정이 완전히 지워질 때 */
    fun eraseAccount(accountId: String)

    /** [day] 보다 앞선 줄을 지운다(보존 기간). 지운 줄 수 */
    fun purgeBefore(day: LocalDate): Int

    companion object {
        val NONE: AccountActivityStore = object : AccountActivityStore {
            override fun recordActive(accountId: String, day: LocalDate) = Unit
            override fun eraseAccount(accountId: String) = Unit
            override fun purgeBefore(day: LocalDate) = 0
        }
    }
}

/** 활동 날짜를 세는 시간대: 설정 `activity.zone`, 비면 `skeleton.time.default-zone`, 그것도 없으면 `time` 모듈의 기본(Asia/Seoul). 프로젝트 모드에서는 프로젝트마다 */
class ActivityZone(private val base: AuthSessionProperties, private val defaultZone: String, projects: ProjectRegistry?) {
    private val scoped = projects?.scopedFrom("skeleton.auth-session", AuthSessionProperties::class.java) { _, properties -> properties }

    fun zone(): ZoneId {
        val project = ProjectContext.currentOrNull()
        val properties = if (scoped != null && project != null) scoped.of(project) else base
        return ZoneId.of(properties.activity.zone.ifBlank { defaultZone })
    }
}

/**
 * 로그인 · 토큰 새로고침 때 오늘의 활동을 남긴다. **인증 경로라 실패해도 로그인을 막지 않는다**(로그에는 예외 종류만). 쓰기 부담을 줄이려고 같은 계정이 같은 날 이미 기록됐으면
 * 저장소를 부르지 않는다(프로세스 메모리의 「마지막으로 기록한 날」 — 다른 인스턴스는 각자 한 번씩 부를 뿐이고 저장소가 멱등이라 한 줄이다).
 */
class AccountActivityRecorder(
    private val store: AccountActivityStore,
    private val zone: () -> ZoneId,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastRecorded = ConcurrentHashMap<String, LocalDate>()

    /** 기록이 실패하면 이 시각까지 저장소를 다시 부르지 않는다 — DB 가 죽었거나 표가 없을 때 로그인 · 새로고침마다 부르고 WARN 을 쏟지 않게. 프로젝트마다(한 프로젝트의 표 문제가 다른 프로젝트의 기록을 멈추지 않게) */
    private val pausedUntil = ConcurrentHashMap<String, Instant>()

    fun record(accountId: String) {
        if (store === AccountActivityStore.NONE) return
        try {
            val now = time.now()
            val project = ProjectContext.scopedKey("")
            if (pausedUntil[project]?.let { now.isBefore(it) } == true) return
            val day = now.atZone(zone()).toLocalDate()
            val key = ProjectContext.scopedKey(accountId)
            if (lastRecorded[key] == day) return
            store.recordActive(accountId, day)
            if (lastRecorded.size >= MAX_REMEMBERED) lastRecorded.clear()   // 메모리 상한 — 비워도 저장소가 멱등이라 한 번씩 더 부를 뿐이다
            lastRecorded[key] = day
        } catch (e: Exception) {
            pausedUntil[ProjectContext.scopedKey("")] = time.now().plus(PAUSE_AFTER_FAILURE)
            log.warn("account activity was not recorded (paused for {}s): {}", PAUSE_AFTER_FAILURE.seconds, e.javaClass.simpleName)
        }
    }

    companion object {
        val NONE = AccountActivityRecorder(AccountActivityStore.NONE, { ZoneId.of("UTC") }, TimeProvider.systemUtc())
        private const val MAX_REMEMBERED = 50_000
        private val PAUSE_AFTER_FAILURE: java.time.Duration = java.time.Duration.ofSeconds(30)
    }
}
