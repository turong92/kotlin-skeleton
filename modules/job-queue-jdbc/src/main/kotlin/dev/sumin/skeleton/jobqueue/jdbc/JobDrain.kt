package dev.sumin.skeleton.jobqueue.jdbc

import java.time.Duration

/**
 * 한 번의 깨우기가 한 일. [timedOut] 이 true 면 시간 한도로 멈췄다(남은 일감이 있을 수 있다 — 다음 호출이 이어받는다).
 * @property processed 처리를 시도한 일감 수(성공 · 재시도 예약 · 실패 포함)
 * @property rounds 돈 판 수(마지막 빈 판 포함)
 */
data class DrainResult(val processed: Int, val rounds: Int, val timedOut: Boolean, val elapsedMillis: Long)

/** 밀린 일감을 시간 한도 안에서 처리하고 끝내는 쪽 — [JobQueueWorker] 가 구현한다. 깨우기 주소(HTTP)는 이것만 안다. */
fun interface JobDrainer {
    fun drain(budget: Duration): DrainResult
}
