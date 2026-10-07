package dev.sumin.skeleton.account.jdbc

import org.springframework.dao.ConcurrencyFailureException
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 교착(deadlock) · 락 대기 시간 초과 · 직렬화 실패는 **트랜잭션 전체**를 잃는다 — 데이터베이스가 한쪽을 골라 되돌린다. 같은 (키, 꼬리표) 에 동시에 넣는 닉네임 발급이 MySQL 에서 이렇게
 * 걸릴 수 있다(유니크 위반을 기다리는 쪽들이 서로를 기다린다). 이것을 그대로 던지면 요청이 500 이 된다 — 바깥(트랜잭션을 연 쪽)에서 몇 번 다시 해 보고, 그래도 안 되면 던진다.
 * 이미 열린 트랜잭션 **안**에서는 다시 하지 않는다 (트랜잭션이 이미 없어졌다 — 그것을 연 바깥이 다시 한다).
 */
internal object LockRetry {
    const val TRIES = 4

    fun <T> run(block: () -> T): T {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return block()
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: ConcurrencyFailureException) {
                if (attempt >= TRIES) throw e
                Thread.sleep(attempt * 10L + (Math.random() * 10).toLong())   // 흩뿌려서 같이 부딪힌 쪽이 또 같이 부딪히지 않게
                attempt++
            }
        }
    }
}
