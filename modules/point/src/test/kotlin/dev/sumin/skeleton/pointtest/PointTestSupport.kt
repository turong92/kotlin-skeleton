package dev.sumin.skeleton.pointtest

import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.point.PointBalance
import dev.sumin.skeleton.point.PointCursor
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointInsufficientException
import dev.sumin.skeleton.point.PointWallet
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.authentication.TestingAuthenticationToken

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class PointTestApplication

fun operator(id: String, vararg roles: String) = TestingAuthenticationToken(id, "n/a", *roles.map { "ROLE_$it" }.toTypedArray())

/** 저장소 없이 컨트롤러의 권한 · 스위치 · 오류 모양만 보려는 지갑 - 원장 규칙은 point-jdbc 의 dbTest 가 본다 */
class FakeWallet : PointWallet {
    val entries = CopyOnWriteArrayList<PointEntry>()
    private var counter = 0

    private fun add(account: String, delta: Long, kind: PointEntryKind, reason: String?, expiresAt: Instant?, source: String, ref: String) =
        PointEntry("pt_${++counter}", account, delta, kind, 0, expiresAt, source, ref, reason, Instant.parse("2026-10-28T00:00:00Z").plusSeconds(counter.toLong())).also { entries += it }

    override fun balance(accountId: String) = PointBalance(entries.filter { it.accountId == accountId }.sumOf { it.delta }, null)

    override fun earn(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointEntry? =
        if (entryOf(sourceType, sourceRef) != null) null else add(accountId, amount, PointEntryKind.EARN, null, expiresAt, sourceType, sourceRef)

    override fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry = error("not used")

    override fun adjust(accountId: String, delta: Long, reason: String, expiresAt: Instant?, operatorId: String): PointEntry {
        if (delta < 0 && balance(accountId).balance < -delta) throw PointInsufficientException(-delta, balance(accountId).balance)
        return add(accountId, delta, PointEntryKind.ADJUST, reason, expiresAt, "ADJUST", "$operatorId:$counter")
    }

    override fun expireDue(now: Instant) = 0

    override fun history(accountId: String, after: PointCursor?, limit: Int): List<PointEntry> =
        entries.filter { it.accountId == accountId }.sortedByDescending { it.createdAt }
            .filter { after == null || it.createdAt < after.createdAt }.take(limit)

    override fun entryOf(sourceType: String, sourceRef: String) = entries.firstOrNull { it.sourceType == sourceType && it.sourceRef == sourceRef }
}

class RecordingAudit : OperatorAuditLog {
    val actions = CopyOnWriteArrayList<OperatorAction>()
    override fun record(action: OperatorAction) {
        actions += action
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PointFakes {
    @Bean fun fakeWallet() = FakeWallet()

    @Bean fun recordingAudit() = RecordingAudit()
}
