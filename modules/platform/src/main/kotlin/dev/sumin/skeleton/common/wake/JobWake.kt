package dev.sumin.skeleton.common.wake

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [at] 에 깨우기 주소(drain)가 한 번 불리게 예약한다. 던지지 않는다 — 실패는 구현이 기록하고 삼킨다. 빈이 없으면 아무도 예약하지 않는다.
 * 지난 시각은 지금으로 본다. 인스턴스가 0대로 내려가는 배포(docs/cloud-run-scale-to-zero.md)에서 일감이 생긴 때 · 이르러질 때에 맞춰 인스턴스를 깨우는 고리다.
 */
fun interface JobWake {
    fun request(at: Instant)
}

/**
 * 트랜잭션이 활성이면 **커밋 뒤에**(롤백이면 부르지 않는다), 아니면 바로 [JobWake.request] 를 부른다 — 커밋되지 않은 일은 깨우기를 예약하지 않는다.
 * 부르는 쪽이 터지지 않게 예외는 삼키고 클래스 이름만 기록한다.
 */
fun JobWake.requestAfterCommit(at: Instant) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = requestSafely(at)
        })
    } else {
        requestSafely(at)
    }
}

private fun JobWake.requestSafely(at: Instant) {
    try {
        request(at)
    } catch (ex: Exception) {
        LoggerFactory.getLogger(JobWake::class.java).warn("Requesting a wake failed: {}", ex.javaClass.simpleName)
    }
}
