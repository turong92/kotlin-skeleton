package dev.sumin.skeleton.account

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** 깨우기(요청 구동)가 계정 정리의 "끄기"(주기 0)를 무시하면, 꺼 둔 앱에서 유예가 끝난 계정이 되돌릴 수 없게 지워진다 — 사고 나는 동작이라 시험한다. */
class AccountPurgeOnRequestTaskTest {
    private fun run(interval: Duration): Int {
        var dispatched = 0
        val task = AccountAutoConfiguration().accountPurgeOnRequestTask(
            AccountProperties(deletion = AccountProperties.Deletion(purgeInterval = interval)),
        ) { dispatched++ }
        task.run()
        return dispatched
    }

    @Test
    fun `a purge interval of zero keeps the wake address from purging accounts`() = assertEquals(0, run(Duration.ZERO))

    @Test
    fun `a running purge interval lets the wake address purge`() = assertEquals(1, run(Duration.ofHours(1)))
}
