package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** 끝난 세션(철회 · 만료)과 토큰을 [AuthSessionProperties.Purge.retention] 이 지난 뒤 지운다 — 표가 무한히 쌓이지 않게 */
class SessionPurge(private val store: SessionStore, private val time: TimeProvider, private val props: AuthSessionProperties.Purge) {
    /** 이번에 지운 세션 수 */
    fun runOnce(): Int = store.purge(time.now().minus(props.retention))
}

/** [SessionPurge] 를 주기마다 돌린다. 여러 인스턴스가 동시에 돌려도 삭제는 멱등이다 */
class SessionPurgeScheduler(private val interval: Duration, private val purge: SessionPurge) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private var executor: ScheduledExecutorService? = null

    val running: Boolean get() = executor != null

    fun start() {
        if (interval.isZero || interval.isNegative) return
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auth-session-purge").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try { purge.runOnce() } catch (e: Exception) { log.warn("session purge run failed: {}", e.javaClass.simpleName) }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    override fun close() { executor?.shutdownNow() }
}

/** 계정이 완전히 지워질 때 그 계정의 세션 행(IP · UA · 기기 이름)을 지운다 — `account` 의 `AccountPurgeService` 가 모아 부른다 */
class SessionErasureListener(private val store: SessionStore) : AccountErasureListener {
    override val name: String = "auth-session"

    override fun erase(request: ErasureRequest) { store.eraseAccount(request.accountId) }
}
