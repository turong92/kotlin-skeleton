package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** 메모리 저장소 - 읽은 횟수를 세고 읽기를 실패시킬 수 있다. 다른 인스턴스의 쓰기는 [put] 을 직접 부른다 */
class FakeAppControlStore : AppControlStore {
    val rows = ConcurrentHashMap<String, AppControlEntry>()
    var reads = 0
    var failReads = false

    override fun <T> transaction(block: () -> T): T = block()

    override fun all(): List<AppControlEntry> {
        reads++
        check(!failReads) { "db down" }
        return rows.values.sortedBy { it.name }
    }

    override fun put(entry: AppControlEntry) {
        rows[entry.name] = entry
    }

    override fun delete(name: String): Boolean = rows.remove(name) != null
}

class MutableTime(var instant: Instant = Instant.parse("2026-11-16T09:00:00Z")) : TimeProvider {
    override fun now(): Instant = instant
}

class RecordingAudit : OperatorAuditLog {
    val actions = CopyOnWriteArrayList<OperatorAction>()

    override fun record(action: OperatorAction) {
        actions += action
    }
}

val testMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()
