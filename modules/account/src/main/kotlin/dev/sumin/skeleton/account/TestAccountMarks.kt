package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import java.time.Instant

/** 운영자가 「시험 계정」으로 표시한 계정 — 누가 언제 표시했는지만 담는다(사유 · 이름 없음). 집계에서 빼는 쪽은 `TestAccountFilter`(platform) */
data class TestAccountMark(val accountId: String, val markedBy: String, val markedAt: Instant)

/** 표시 저장소 포트 — `account-jdbc` 가 `account_test_marks` 표로 구현하고, 메모리 구현이 로컬 · 시험 기본이다 */
interface TestAccountMarks {
    /** 표시한다. 이미 표시된 계정이면 아무것도 바꾸지 않고 false(처음 표시한 사람 · 시각을 지킨다) */
    fun mark(accountId: String, by: String, at: Instant): Boolean

    /** 표시를 지운다. 표시가 없었으면 false */
    fun clear(accountId: String): Boolean

    fun find(accountId: String): TestAccountMark?
}

class InMemoryTestAccountMarks : TestAccountMarks {
    private val rows = java.util.concurrent.ConcurrentHashMap<String, TestAccountMark>()

    override fun mark(accountId: String, by: String, at: Instant): Boolean = rows.putIfAbsent(accountId, TestAccountMark(accountId, by, at)) == null

    override fun clear(accountId: String): Boolean = rows.remove(accountId) != null

    override fun find(accountId: String): TestAccountMark? = rows[accountId]
}

/**
 * 시험 계정 표시 · 해제 — 호출자의 권한은 HTTP 층이 본다. 표시가 실제로 바뀐 때만 [OperatorAuditLog] 에 한 줄을 남기고(같은 트랜잭션 안), 이미 그 상태면 아무것도 쓰지 않는다.
 * 지워진(ERASED) 계정은 표시할 수 없다(410) — 지워진 뒤에는 집계에 계정으로 남아 있지 않다.
 */
class TestAccountService(private val core: AccountCore, private val marks: TestAccountMarks, private val audit: OperatorAuditLog) {
    fun mark(actorId: String, targetId: String) {
        val target = core.accounts.findById(targetId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        if (target.status == AccountStatus.ERASED) throw AccountException(AccountErrorCode.ERASED)
        core.atomic.run {
            if (marks.mark(targetId, actorId, core.time.now())) audit.record(OperatorAction(actorId, SET, "account", targetId))
        }
    }

    /** 지워진 계정이어도 표시를 풀 수는 있다 — 없는 계정이면 404 */
    fun clear(actorId: String, targetId: String) {
        core.accounts.findById(targetId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        core.atomic.run {
            if (marks.clear(targetId)) audit.record(OperatorAction(actorId, CLEARED, "account", targetId))
        }
    }

    fun find(targetId: String): TestAccountMark? {
        core.accounts.findById(targetId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        return marks.find(targetId)
    }

    companion object {
        const val SET = "account.test-mark.set"
        const val CLEARED = "account.test-mark.cleared"
    }
}
