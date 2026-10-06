package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

data class Allowance(val allowed: Boolean, val retryAfterSeconds: Long)

/**
 * 계정 흐름의 요청 한도 — platform 의 [RateLimitStore] 위에 (redis-rate-limit 이 저장소를 내면 Redis, 아니면 이 인스턴스의 인메모리).
 * 키는 값(IP · 이메일)의 해시라서 저장소에 이메일이 그대로 남지 않는다.
 */
class AccountRateLimits(
    private val store: () -> RateLimitStore,
    private val time: TimeProvider,
) {
    fun acquire(scope: String, key: String, capacity: Int, window: Duration): Allowance {
        val now = time.now()
        val decision = store().consume("account:$scope:${digest(key)}", capacity, window.toMillis(), now)
        val retry = (decision.resetAt.epochSecond - now.epochSecond).coerceAtLeast(1)
        return Allowance(decision.allowed, retry)
    }

    companion object {
        fun digest(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))).take(24)

        fun inMemory(time: TimeProvider) = AccountRateLimits({ FALLBACK }, time)

        private val FALLBACK = InMemoryFixedWindowRateLimitStore()
    }
}

/** 요청 한도 초과 — 429 + `Retry-After` 헤더(현재 요청에 붙일 수 있으면) + `data.retryAfterSeconds` */
class RateLimitedException(val retryAfterSeconds: Long, errorCode: dev.sumin.skeleton.common.ErrorCode = AccountErrorCode.RATE_LIMITED) :
    dev.sumin.skeleton.common.ApplicationException(
        message = "Too many requests",
        errorCode = errorCode,
        data = mapOf("retryAfterSeconds" to retryAfterSeconds),
    ) {
    init { RetryAfter.set(retryAfterSeconds) }
}

internal object RetryAfter {
    fun set(seconds: Long) {
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.response?.setHeader("Retry-After", seconds.toString())
    }
}

/** 캡차 검증 고리 — `captcha-turnstile` 이 있으면 그 검증기로 이어지고, 없으면 검증하지 않는다 */
fun interface AccountCaptcha {
    fun verify(token: String?, ip: String?, action: String): Boolean
}

/**
 * `skeleton.account.captcha.required` 가 정한다: false(기본)면 검증기가 있어도 부르지 않는다(외부 호출 없음), true 면 모든 요청이 검증을 통과해야 하고
 * **검증기가 없으면 실패로 닫힌다**(통과시키지 않는다 — stage · prod 가드도 같은 이유로 기동을 막는다). 통과하지 못하면 ACCOUNT.CAPTCHA_FAILED (토큰이 없어도 같다).
 * 서비스는 값싼 IP 한도를 **먼저** 검사한 뒤 이것을 부른다 — 폭주가 외부 검증 호출을 요청 수만큼 만들지 않게.
 */
class CaptchaGate(private val captcha: AccountCaptcha?, private val required: Boolean = captcha != null) {
    fun check(token: String?, ip: String?, action: String) {
        if (!required) return
        if (captcha == null || !captcha.verify(token, ip, action)) throw AccountException(AccountErrorCode.CAPTCHA_FAILED)
    }

    val active: Boolean get() = required && captcha != null
}

/**
 * 요청 스레드가 기다리지 않게 일을 뒤로 넘긴다 — 재설정 · 재전송 · 가입 메일은 "있는지 찾고 → 토큰 → 메일" 을 여기서 하므로
 * 요청의 응답 시간이 계정 존재 여부와 무관하다. 기본 구현은 이 프로세스의 작은 풀이다 (종료 때는 기다려 마치지만, 비정상 종료로 처리 못 한 일은 사라진다 —
 * 사용자는 다시 요청한다. **계정 행 자체는 여기 있지 않다**: 가입은 요청 스레드에서 저장한다). 내구성이 필요하면 앱이 같은 타입의 빈으로 job-queue 같은 곳에 넣는다.
 */
fun interface AccountTaskRunner {
    fun run(label: String, task: Runnable)

    companion object {
        /** 부른 스레드에서 바로 — 시험 · 동기 모드용 */
        val DIRECT = AccountTaskRunner { _, task -> task.run() }
    }
}

/**
 * 이 프로세스의 작은 풀. 넘치면 **버리지 않고 부른 스레드가 직접 한다**(밀어내기) — 과부하일 때만 응답이 느려진다.
 * 닫힐 때(배포 · 종료)는 대기 중인 일을 [shutdownWait] 동안 기다려 마친다 (데몬 스레드라서 그냥 두면 JVM 과 함께 사라진다).
 */
class ExecutorAccountTaskRunner(threads: Int = 2, queue: Int = 1000, private val shutdownWait: Duration = Duration.ofSeconds(10)) : AccountTaskRunner, AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = ThreadPoolExecutor(
        threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(queue),
        { r -> Thread(r, "account-task").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    override fun run(label: String, task: Runnable) {
        val guarded = Runnable {
            try { task.run() } catch (e: Exception) { log.warn("account task '{}' failed: {}", label, e.javaClass.simpleName) }
        }
        try {
            pool.execute(guarded)
        } catch (_: RejectedExecutionException) {
            // 이미 닫는 중 — 마지막 한 건도 잃지 않게 이 스레드에서
            guarded.run()
        }
    }

    override fun close() {
        pool.shutdown()
        if (!pool.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
            val dropped = pool.shutdownNow().size
            log.warn("account tasks did not finish within {}; {} queued task(s) were abandoned", shutdownWait, dropped)
        }
    }
}
