package dev.sumin.skeleton.review

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal object AfterCommit {
    private val log = LoggerFactory.getLogger(AfterCommit::class.java)

    /** 열린 트랜잭션이 있으면 커밋 뒤로 미루고(롤백이면 하지 않는다), 없으면 바로 한다. 실패는 로그만 남긴다 - 알림이 운영 처리를 되돌리지 않는다 */
    fun run(action: () -> Unit) {
        val safe = {
            try {
                action()
            } catch (e: Exception) {
                log.warn("review notification failed: {}", e.message)
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
