package dev.sumin.skeleton.badge

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 받았어요 알림 자리 — 앱 수여 · 운영 수여에서만 불린다(지급기 경로는 `reward` 가 이미 알린다 - 중복 방지). 커밋 뒤에 불리고, 던져도 수여는 성공한다.
 * 기본은 아무것도 하지 않는다. `notification` 이 있으면 받은편지함으로 보낸다([dev.sumin.skeleton.badge.notification.NotificationBadgeNotifier]).
 */
fun interface BadgeNotifier {
    /** [eventId] 는 내역 줄 id — 알림의 중복을 접는 키로 쓴다(회수 뒤 다시 받아도 알림은 새로 나간다) */
    fun awarded(accountId: String, badge: BadgeDefinition, source: BadgeSource, eventId: String)
}

internal object AfterCommit {
    private val log = LoggerFactory.getLogger(AfterCommit::class.java)

    /** 열린 트랜잭션이 있으면 커밋 뒤로 미루고(롤백이면 하지 않는다), 없으면 바로 한다. 실패는 로그만 남긴다 */
    fun run(action: () -> Unit) {
        val safe = {
            try {
                action()
            } catch (e: Exception) {
                log.warn("badge notification failed: {}", e.message)
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = safe()
            })
        } else {
            safe()
        }
    }
}
