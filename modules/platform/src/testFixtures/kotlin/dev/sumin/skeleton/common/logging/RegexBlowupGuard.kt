package dev.sumin.skeleton.common.logging

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.fail

/**
 * 정규식이 입력 길이에 **제곱 · 지수로** 느려지는지(ReDoS)를 잡는 시험 도우미. 벽시계 시간의 **비율**(작은 입력 대 큰 입력)로 재면 부하가 큰 기계에서 잡음이 비율을 지배해 거짓으로 실패한다 —
 * 그래서 비율을 보지 않고, 선형이면 수십 ms 에 끝나지만 제곱이면 수 초~수십 초가 드는 **큰 입력** 하나를 돌려 [limitMillis] 안에 **끝나는지**만 본다.
 * 한도는 선형 비용의 수십~수백 배라 부하에도 안 걸리고, 제곱 · 지수는 한도를 훨씬 넘는다. 끝나지 않는 실행(지수)이 시험을 붙잡지 않게 daemon 스레드에서 돌리고 기다림만 자른다.
 */
object RegexBlowupGuard {
    /** 선형이면 입력 하나가 1초 안팎, 제곱이면 토큰 경계에서만 시작하는 패턴이라도 10¹¹ 단계 이상이라 한도를 크게 넘는 길이 */
    const val DEFAULT_N = 1_000_000
    const val DEFAULT_LIMIT_MILLIS = 5_000L

    /** 인터럽트되면 던지는 CharSequence — 시간 초과 뒤에도 정규식이 코어를 계속 쓰지 않게, 자기 시험처럼 일부러 느린 패턴을 돌릴 때 입력을 이걸로 감싼다 */
    fun interruptible(text: CharSequence): CharSequence = object : CharSequence by text {
        override fun get(index: Int): Char {
            if (Thread.currentThread().isInterrupted) throw CancellationException("interrupted")
            return text[index]
        }
    }

    fun assertFinishesInTime(what: String, n: Int = DEFAULT_N, limitMillis: Long = DEFAULT_LIMIT_MILLIS, run: (Int) -> Unit) {
        val done = CompletableFuture<Unit>()
        val worker = Thread({
            try {
                run(n)
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }, "regex-blowup-guard").apply { isDaemon = true }
        worker.start()
        try {
            done.get(limitMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            worker.interrupt()   // [interruptible] 로 감싼 입력이면 여기서 멈춘다
            fail("$what: $n chars did not finish within ${limitMillis}ms — the pattern backtracks quadratically or worse (ReDoS)")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
