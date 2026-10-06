package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.AccountTransaction
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 계정을 만드는 쓰기와 같이 가야 하는 쓰기(약관 동의 기록)를 한 DB 트랜잭션으로 묶는다. 저장소들의 `TransactionTemplate` 은 같은 `DataSource` 의
 * 열린 트랜잭션에 합류하므로 [block] 안의 쓰기가 하나로 커밋되거나 하나로 되돌려진다.
 */
class JdbcAccountTransaction(transactionManager: PlatformTransactionManager) : AccountTransaction {
    private val template = TransactionTemplate(transactionManager)

    @Suppress("UNCHECKED_CAST")
    override fun <T> run(block: () -> T): T = template.execute { block() } as T
}
