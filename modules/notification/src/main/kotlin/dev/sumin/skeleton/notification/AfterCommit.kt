package dev.sumin.skeleton.notification

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.ClassUtils

/** `spring-tx` 클래스가 없는 앱에서도 뜰 수 있게(예: 직접 구성) 클래스가 있을 때만 [Synchronizations] 를 건드린다 */
internal object AfterCommit {
    private val available = ClassUtils.isPresent(
        "org.springframework.transaction.support.TransactionSynchronizationManager",
        AfterCommit::class.java.classLoader,
    )

    /**
     * 트랜잭션 동기화가 켜져 있으면 [action] 을 커밋 뒤로 미루고 true, 아니면 아무것도 하지 않고 false (호출자가 바로 한다).
     * 다른 동기화의 `afterCommit` 안에서 부르면 새로 등록한 `afterCommit` 은 불리지 않으므로(이미 그 단계다) `afterCompletion(커밋됨)` 에서 대신 한다 -
     * 두 곳 중 먼저 불린 한 번만 돈다. 롤백이면 하지 않는다.
     */
    fun runAfterCommit(action: () -> Unit): Boolean = available && Synchronizations.register(action)

    private object Synchronizations {
        fun register(action: () -> Unit): Boolean {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return false
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    private var done = false

                    override fun afterCommit() = runOnce()

                    override fun afterCompletion(status: Int) {
                        if (status == TransactionSynchronization.STATUS_COMMITTED) runOnce()
                    }

                    private fun runOnce() {
                        if (done) return
                        done = true
                        action()
                    }
                },
            )
            return true
        }
    }
}
