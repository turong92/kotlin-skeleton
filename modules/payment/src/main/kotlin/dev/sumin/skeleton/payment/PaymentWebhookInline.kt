package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.project.ProjectContext
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/**
 * 웹훅을 받은 요청 안에서 그 처리 작업을 한 번 바로 돌려 본다(`webhook.inline`). 수신이 커밋된 **뒤에** 부른다.
 *
 * - 처리는 [PaymentTasksNow] 가 작업을 **임대해서** 돌린다 - 일꾼이 같은 작업을 동시에 집을 수 없고, 인라인이 끝낸 작업은 일꾼이 다시 돌리지 않는다.
 * - 요청은 [timeBudget] 까지만 기다린다. 넘으면 기다리기를 멈추고 수신은 바로 `200` 이다 - 별도 스레드의 처리는 끝까지 이어지고 작업은 그 스레드가 임대하고 있다(멈춰 버리면 임대 만료로 일꾼이 되찾는다).
 * - 실패(결제사 조회 실패 포함)는 삼킨다: 작업이 큐의 재시도 · 백오프에 남아 지금처럼 일꾼이 맡는다. 던지지 않는다.
 * - 스레드는 [MAX_THREADS] 개까지 - 가득 차면 시도하지 않고(일꾼이 맡는다) 요청 스레드를 쌓지 않는다.
 */
class PaymentWebhookInline(
    private val now: PaymentTasksNow,
    private val timeBudget: Duration,
    private val executor: ExecutorService = defaultExecutor(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun attempt(eventId: Long) {
        val future = try {
            executor.submit(ProjectContext.carry { now.runNow(PaymentTaskTypes.WEBHOOK, eventId.toString()) })
        } catch (_: RejectedExecutionException) {
            log.info("payment webhook inline attempt skipped: all {} inline threads are busy", MAX_THREADS)
            return
        }
        try {
            future.get(timeBudget.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            log.info("payment webhook inline attempt is over its {} budget; answering the provider now, the attempt goes on", timeBudget)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.warn("payment webhook inline attempt failed exception={}", (e.cause ?: e).javaClass.simpleName)
        }
    }

    companion object {
        const val MAX_THREADS = 8

        private fun defaultExecutor(): ExecutorService {
            val count = AtomicInteger()
            return ThreadPoolExecutor(0, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
                Thread(r, "payment-webhook-inline-${count.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
