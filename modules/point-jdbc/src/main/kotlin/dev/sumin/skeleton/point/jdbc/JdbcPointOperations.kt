package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointCompensation
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointErrorCode
import dev.sumin.skeleton.point.PointOperation
import dev.sumin.skeleton.point.PointOperationCodes
import dev.sumin.skeleton.point.PointOperationKind
import dev.sumin.skeleton.point.PointOperationLog
import dev.sumin.skeleton.point.PointOperationResult
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointOperations
import dev.sumin.skeleton.point.PointReceipt
import dev.sumin.skeleton.point.PointSourceConflictException
import dev.sumin.skeleton.point.PointStore
import dev.sumin.skeleton.point.StorePointWallet
import java.security.MessageDigest
import java.time.Instant
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * [PointOperations] 의 JDBC 구현 - 새 오케스트레이터가 아니라 [PointStore] 위에 영수증 · 시도 · 결과 전이를 얹을 뿐이다. 순서(계약은 [PointOperations]):
 * 활성 트랜잭션 안에서는 거절한다(합류하면 자기 교착 · 커넥션 둘 · 낡은 스냅샷). ① 영수증 → ② 시도 열기(각자 짧은 트랜잭션) → ③ 원장 쓰기 + 성공 표시(+ 보상 완료)를 **자기가 연 한 트랜잭션**으로 → ④ 확정 실패는 짧은 트랜잭션으로 적는다.
 * ③ 에서 도메인 거절(`POINT.*`)은 콜백 안에서 잡아 값으로 돌려준다(원장은 아무것도 바꾸지 않았다). 그 밖의 예외는 결과를 모르므로 UNKNOWN 으로 적고 던진다.
 */
class JdbcPointOperations(
    private val store: PointStore,
    private val log: PointOperationLog,
    transactions: PlatformTransactionManager,
    private val time: TimeProvider,
) : PointOperations {
    private val tx = TransactionTemplate(transactions)

    override fun spend(actor: String, accountId: String, amount: Long, sourceType: String, sourceRef: String): PointOperationResult {
        requireNoTransaction()
        requireAmount(amount)
        val receipt = receipt(actor, accountId, PointOperationKind.SPEND, amount, sourceType, sourceRef, null, null)
        return execute(receipt, gate = { null }) { now -> store.spend(accountId, amount, PointEntryKind.SPEND, sourceType, sourceRef, null, now) }
    }

    override fun earn(actor: String, accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointOperationResult {
        requireNoTransaction()
        requireAmount(amount)
        val receipt = receipt(actor, accountId, PointOperationKind.EARN, amount, sourceType, sourceRef, null, null)
        return execute(receipt, gate = { null }) { now ->
            // 같은 키의 다시 부름이면 원장은 null - 이미 있는 줄이 이 작업의 결과다
            store.earn(accountId, amount, expiresAt, PointEntryKind.EARN, sourceType, sourceRef, null, now) ?: checkNotNull(store.findBySource(sourceType, sourceRef)) { "point line vanished" }
        }
    }

    override fun cancelSpend(
        actor: String,
        accountId: String,
        spendSourceType: String,
        spendSourceRef: String,
        amount: Long?,
        sourceType: String,
        sourceRef: String,
    ): PointOperationResult {
        requireNoTransaction()
        if (amount != null) requireAmount(amount)
        // 원 차감이 이 층에서 만든 작업이면 연결한다(보상). 지갑으로 직접 한 차감이면 연결 없이 원장만 본다
        val origin = log.findBySource(spendSourceType, spendSourceRef)?.takeIf { it.kind == PointOperationKind.SPEND && it.accountId == accountId }
        val receipt = receipt(actor, accountId, PointOperationKind.CANCEL_SPEND, amount, sourceType, sourceRef, spendSourceType, spendSourceRef, origin?.operationId)
        if (origin != null && origin.compensation != PointCompensation.DONE) log.setCompensation(origin.operationId, PointCompensation.REQUESTED)
        // 보상은 원 차감이 **원장에 있다고 확인된** 건에만 한다 - 확인되지 않은 건(불명 · 포기)은 먼저 원장으로 가르고, 그래도 줄이 없으면 기다린다
        val gate: () -> Stop? = {
            if (origin == null) {
                null
            } else {
                val confirmed = if (origin.status == PointOperationStatus.SUCCEEDED) origin else resolve(origin.operationId)
                when (confirmed?.status) {
                    PointOperationStatus.SUCCEEDED -> null
                    PointOperationStatus.FAILED -> Stop(PointOperationStatus.FAILED, PointErrorCode.SPEND_NOT_FOUND.code)   // 원 차감은 확실히 실패했다 - 되돌릴 것이 없다
                    else -> Stop(PointOperationStatus.UNKNOWN, PointOperationCodes.ORIGIN_UNRESOLVED)
                }
            }
        }
        // 보상 완료 표시는 환원을 쓰는 원장 트랜잭션 안에서 한다 - 롤백되면 DONE 도 사라진다
        val result = execute(receipt, gate, afterSuccess = { origin?.let { log.setCompensation(it.operationId, PointCompensation.DONE) } }) { now ->
            store.cancelSpend(accountId, spendSourceType, spendSourceRef, amount, sourceType, sourceRef, now)
        }
        if (origin != null) {
            when (result.status) {
                PointOperationStatus.SUCCEEDED -> if (log.find(origin.operationId)?.compensation != PointCompensation.DONE) log.setCompensation(origin.operationId, PointCompensation.DONE)   // 이미 끝난 취소의 다시 부름
                PointOperationStatus.FAILED ->
                    log.setCompensation(origin.operationId, if (result.operation.resultCode == PointErrorCode.SPEND_NOT_FOUND.code && originFailed(origin)) PointCompensation.NONE else PointCompensation.FAILED)
                else -> Unit   // 보상 요청 상태로 남는다
            }
        }
        return result
    }

    private fun originFailed(origin: PointOperation) = log.find(origin.operationId)?.status == PointOperationStatus.FAILED

    override fun resolve(operationId: String): PointOperation? {
        requireNoTransaction()
        val op = log.find(operationId) ?: return null
        if (op.status == PointOperationStatus.SUCCEEDED || op.status == PointOperationStatus.FAILED) return op
        val now = time.now()
        val line = store.findBySource(op.sourceType, op.sourceRef)
        if (line != null && matches(op, line)) {
            log.recoverSucceeded(operationId, line.id, now)   // 포기한 건도 - 느리게 진행되던 원장 쓰기가 늦게 커밋되거나 지갑으로 같은 키를 직접 쓴 경우
        } else if (op.status == PointOperationStatus.ACCEPTED || op.status == PointOperationStatus.PROCESSING) {
            log.markUnknown(operationId, op.attempts.takeIf { it > 0 }, PointOperationCodes.UNKNOWN, now)
        }
        return log.find(operationId)
    }

    override fun find(operationId: String): PointOperation? {
        requireNoTransaction()
        return log.find(operationId)
    }

    override fun findBySource(sourceType: String, sourceRef: String): PointOperation? {
        requireNoTransaction()
        return log.findBySource(sourceType, sourceRef)
    }

    override fun abandon(operationId: String) {
        requireNoTransaction()
        log.abandon(operationId, time.now())
    }

    /** 활성 트랜잭션 안에서는 쓰지 않는다 - 합류하면 자기 교착 · 커넥션 둘 · 낡은 스냅샷이 따라온다. 묶어야 하면 [dev.sumin.skeleton.point.PointWallet] */
    private fun requireNoTransaction() = check(!TransactionSynchronizationManager.isActualTransactionActive()) {
        "PointOperations must not be called inside a transaction (use PointWallet to join one with other writes)"
    }

    // ---- 내부 ----

    private sealed interface Outcome
    private class Done(val entry: PointEntry) : Outcome
    private class Refused(val code: String) : Outcome

    /** 원장에 닿지 않고 멈춘다 - [status] 는 UNKNOWN(기다린다) 또는 FAILED(되돌릴 것이 없다) */
    private class Stop(val status: PointOperationStatus, val code: String)

    /**
     * 작업 하나를 끝까지 돌린다. [gate] 가 [Stop] 을 돌려주면 원장에 닿지 않고 그 상태(UNKNOWN 이면 기다린다, FAILED 면 되돌릴 것이 없다)로 멈춘다(보상 전 확인용). [write] 는 원장 쓰기이고 성공한 줄을 돌려준다.
     */
    private fun execute(receipt: PointReceipt, gate: () -> Stop?, afterSuccess: () -> Unit = {}, write: (Instant) -> PointEntry): PointOperationResult {
        val accepted = log.accept(receipt, time.now())
        val existing = accepted.operation
        if (!accepted.created) {
            if (existing.fingerprint != receipt.fingerprint) throw PointSourceConflictException()   // 같은 키에 다른 요청 - 기록은 그대로
            if (isClosed(existing)) return answer(existing)
        }
        val n = try {
            // 이번에 만든 작업이 아니면 같은 키 재시도다 - 조회 잡을 다시 건다(먼저 걸린 잡은 끝났거나 포기했을 수 있고, 영수증만 쓰고 죽은 작업도 이 길로 되살아난다)
            log.beginAttempt(existing.operationId, time.now(), reschedule = !accepted.created)
        } catch (e: IllegalStateException) {
            // 그 사이 다른 호출이 끝냈다
            val current = log.find(existing.operationId)
            if (current != null && isClosed(current)) return answer(current)
            throw e
        }
        gate()?.let { stop ->
            val at = time.now()
            if (stop.status == PointOperationStatus.FAILED) log.fail(existing.operationId, n, stop.code, at) else log.markUnknown(existing.operationId, n, stop.code, at)
            return PointOperationResult(existing.copy(status = stop.status, resultCode = stop.code, attempts = n, lastAttemptAt = at), null)
        }
        val now = time.now()
        val outcome = try {
            tx.execute<Outcome> {
                try {
                    val entry = write(now)
                    log.succeed(existing.operationId, n, entry.id, now)
                    afterSuccess()
                    Done(entry)
                } catch (e: ApplicationException) {
                    // 원장이 확정적으로 거절했다(아무것도 바뀌지 않았다) - 던지지 않고 값으로
                    val code = e.errorCode
                    if (code is PointErrorCode) Refused(code.code) else throw e
                }
            }
        } catch (e: Exception) {
            // 커밋 결과를 모른다(타임아웃 · 응답 유실 · 롤백) - 확정 실패가 아니다. 원장으로 가르려고 불명으로 적고 그대로 던진다
            log.markUnknown(existing.operationId, n, PointOperationCodes.UNKNOWN, time.now())
            throw e
        }
        return when (outcome) {
            // 결과는 다시 읽지 않고 방금 적은 대로 만든다 - 한 번 덜 읽고, 방금 커밋한 값과 어긋날 일이 없다
            is Done -> PointOperationResult(
                existing.copy(status = PointOperationStatus.SUCCEEDED, attempts = n, lastAttemptAt = now, resultEntryId = outcome.entry.id, resultCode = PointOperationCodes.OK, finishedAt = now),
                outcome.entry,
            )
            is Refused -> {
                val at = time.now()
                log.fail(existing.operationId, n, outcome.code, at)
                PointOperationResult(existing.copy(status = PointOperationStatus.FAILED, attempts = n, lastAttemptAt = now, resultCode = outcome.code, finishedAt = at), null)
            }
            null -> error("transaction returned nothing")
        }
    }

    private fun isClosed(op: PointOperation) = op.status == PointOperationStatus.SUCCEEDED || op.status == PointOperationStatus.FAILED

    private fun answer(op: PointOperation): PointOperationResult =
        PointOperationResult(op, if (op.status == PointOperationStatus.SUCCEEDED) store.findBySource(op.sourceType, op.sourceRef) else null)

    /** 원장 줄이 이 작업의 요청과 맞는가 - 종류와 부호 있는 금액(남은 전부를 되돌린 취소는 금액을 대조하지 않는다) */
    private fun matches(op: PointOperation, line: PointEntry): Boolean = line.accountId == op.accountId && when (op.kind) {
        PointOperationKind.SPEND -> line.kind == PointEntryKind.SPEND && line.delta == -(op.amount ?: return false)
        PointOperationKind.EARN -> line.kind == PointEntryKind.EARN && line.delta == op.amount
        PointOperationKind.CANCEL_SPEND -> line.kind == PointEntryKind.RESTORE && (op.amount == null || line.delta == op.amount)
    }

    private fun receipt(
        actor: String,
        accountId: String,
        kind: PointOperationKind,
        amount: Long?,
        sourceType: String,
        sourceRef: String,
        spendType: String?,
        spendRef: String?,
        origin: String? = null,
    ): PointReceipt {
        require(actor.isNotBlank() && actor.length <= MAX_ACTOR) { "actor is required, up to $MAX_ACTOR characters" }
        return PointReceipt(accountId, kind, amount, sourceType, sourceRef, fingerprint(kind, accountId, amount, spendType, spendRef), actor, origin)
    }

    private fun requireAmount(amount: Long) = require(amount in 1..StorePointWallet.MAX_AMOUNT) { "amount must be 1..${StorePointWallet.MAX_AMOUNT}" }

    private companion object {
        const val MAX_ACTOR = 64

        /** 요청 지문 - 종류 · 계정 · 금액 · 원 차감 키. 유효기간은 보지 않는다(원장의 멱등 규칙과 같다) */
        fun fingerprint(kind: PointOperationKind, accountId: String, amount: Long?, spendType: String?, spendRef: String?): String {
            val text = listOf("v1", kind.name, accountId, amount?.toString() ?: "-", spendType ?: "-", spendRef ?: "-").joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
