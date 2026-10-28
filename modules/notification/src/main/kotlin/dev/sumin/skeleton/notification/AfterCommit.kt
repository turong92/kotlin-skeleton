package dev.sumin.skeleton.notification

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.ClassUtils

/** `spring-tx` 는 선택 의존(`compileOnly`)이라 클래스가 있을 때만 [Synchronizations] 를 건드린다 */
internal object AfterCommit {
    private val available = ClassUtils.isPresent(
        "org.springframework.transaction.support.TransactionSynchronizationManager",
        AfterCommit::class.java.classLoader,
    )

    /** 트랜잭션 동기화가 켜져 있으면 [action] 을 커밋 뒤로 미루고 true, 아니면 아무것도 하지 않고 false (호출자가 바로 한다) */
    fun runAfterCommit(action: () -> Unit): Boolean = available && Synchronizations.register(action)

    private object Synchronizations {
        fun register(action: () -> Unit): Boolean {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return false
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                },
            )
            return true
        }
    }
}
