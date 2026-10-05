package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 주인에게 알릴 일을 내놓는 입구. **업무 흐름에 절대 던지지 않는다** — 안에서 터지면 로그만 남긴다.
 *
 * - 부르는 쪽이 트랜잭션 안이면 **커밋 뒤에** 내보낸다(롤백되면 알리지 않는다 — 일어나지 않은 일). 실패를 알리는 경보는 트랜잭션이
 *   롤백돼도 남아야 하니 [immediate] 로 부른다
 * - [detail] 에 비밀 값을 넣지 않는다. 그래도 `LogMasker` 를 거쳐 한 줄로 접고 1,000자로 자른다
 * - 같은 (종류 · [key])는 최소 간격 안에 다시 오면 접는다 — 간격이 지나 다시 오면 접은 수를 실어 한 번 더 나간다
 */
interface OwnerAlerts {
    fun emit(kind: AlertKind, key: String = "", detail: String = "", immediate: Boolean = false)

    companion object {
        /** 아무것도 하지 않는다 — 단위 테스트 · 입구 없는 조립용 */
        val NONE: OwnerAlerts = object : OwnerAlerts {
            override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) = Unit
        }
    }
}

class DefaultOwnerAlerts(
    private val store: AlertStore,
    private val channels: List<AlertChannel>,
    private val properties: AlertProperties,
    private val time: TimeProvider,
    /** 채널 보내기는 부르는 스레드가 아니라 여기서 — 웹훅이 느려도 요청이 기다리지 않는다 */
    private val handoff: (Runnable) -> Unit = { Thread.ofVirtual().name("owner-alert").start(it) },
    private val pause: (Duration) -> Unit = { Thread.sleep(it) },
) : OwnerAlerts {
    private val log = LoggerFactory.getLogger(javaClass)
    private val environment = properties.environment.ifBlank { "default" }
    private val lastDirect = ConcurrentHashMap<String, Instant>()

    override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) {
        try {
            val cleanKey = key.trim().replace(WHITESPACE, " ").take(KEY_MAX)
            val cleanDetail = LogMasker.current.mask(detail).replace(WHITESPACE, " ").trim().take(DETAIL_MAX)
            logLine(kind, cleanKey, cleanDetail)
            if (!immediate && TransactionSynchronizationManager.isSynchronizationActive() &&
                TransactionSynchronizationManager.isActualTransactionActive()
            ) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = dispatch(kind, cleanKey, cleanDetail)
                })
            } else {
                dispatch(kind, cleanKey, cleanDetail)
            }
        } catch (e: Exception) {
            log.warn("owner alert emit failed kind={}: {}", kind.name, e.javaClass.simpleName)
        }
    }

    private fun dispatch(kind: AlertKind, key: String, detail: String) {
        try {
            val now = time.now()
            val recorded = store.record(kind, key, kind.severity, kind.title, detail, now, properties.interval(kind))
            if (!recorded.send) return
            val message = AlertMessage(kind.name, key, kind.severity, kind.title, detail, recorded.suppressedFolded, now, environment)
            channels.forEach { channel -> handoff(Runnable { deliver(channel, message) }) }
        } catch (e: Exception) {
            log.warn("owner alert dispatch failed kind={}: {}", kind.name, e.javaClass.simpleName)
            if (kind.directFallback) directFallback(kind, detail)
        }
    }

    /** 한 채널의 보내기 — 시도 횟수만큼, 시도 사이는 곱절로 쉰다. 끝내 안 되면 로그만 (다른 채널은 영향 없다) */
    private fun deliver(channel: AlertChannel, message: AlertMessage) {
        val attempts = properties.delivery.attempts.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            try {
                channel.send(message)
                return
            } catch (e: Exception) {
                log.warn("owner alert via {} failed (attempt {}/{}) kind={}: {}", channel.name, attempt, attempts, message.kind, e.message ?: e.javaClass.simpleName)
                if (attempt < attempts) pause(properties.delivery.backoff.multipliedBy(1L shl (attempt - 1)))
            }
        }
    }

    /**
     * 기록(DB)이 막혀도 나가야 하는 경보([AlertKind.directFallback]) — DB 가 죽으면 5xx 가 몰리는 게 보통이라 DB 위의 길로만 보내면 필요한 때 못 운다.
     * 웹훅으로 **직접** 한 번(메일은 SMTP 가 필요하다). 표의 간격 접기가 없으니 메모리로 같은 간격을 둔다
     */
    private fun directFallback(kind: AlertKind, detail: String) {
        val uri = properties.webhookUri ?: return
        val now = time.now()
        val last = lastDirect[kind.name]
        if (last != null && now < last.plus(properties.interval(kind))) return
        lastDirect[kind.name] = now
        val text = "$detail\n(recording was unavailable, sent directly)".take(DETAIL_MAX)
        DirectWebhook.post(uri, "[$environment][${kind.severity}] ${kind.title}", text, kind.severity, "alert ${kind.name}")
    }

    /** 채널이 없어도 줄은 남는다 — 심각도대로 INFO · WARN · ERROR */
    private fun logLine(kind: AlertKind, key: String, detail: String) {
        val line = "owner alert [{}] kind={} key={} detail={}"
        when (kind.severity) {
            AlertSeverity.INFO -> log.info(line, environment, kind.name, key, detail)
            AlertSeverity.WARN -> log.warn(line, environment, kind.name, key, detail)
            else -> log.error(line, environment, kind.name, key, detail)
        }
    }

    private companion object {
        const val KEY_MAX = 80
        const val DETAIL_MAX = 1000
        val WHITESPACE = Regex("\\s+")
    }
}
