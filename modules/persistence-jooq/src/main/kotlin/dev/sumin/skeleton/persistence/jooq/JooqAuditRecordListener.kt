package dev.sumin.skeleton.persistence.jooq

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import org.jooq.RecordContext
import org.jooq.RecordListener
import org.jooq.UpdatableRecord

/**
 * 레코드에 `created_at` / `updated_at` 필드가 있으면 insert/update 때 자동으로 채운다
 * (persistence-jdbc 의 audit 콜백과 같은 계약, 컬럼 이름 기반이라 생성 코드에 의존하지 않음).
 * 필드 타입은 코드 생성 forcedType 에 따라 Instant 이다.
 */
class JooqAuditRecordListener(private val timeProvider: TimeProvider) : RecordListener {
    override fun insertStart(ctx: RecordContext) {
        val record = ctx.record() as? UpdatableRecord<*> ?: return
        val now = timeProvider.now()
        setIfPresent(record, CREATED_AT, now)
        setIfPresent(record, UPDATED_AT, now)
    }

    override fun updateStart(ctx: RecordContext) {
        val record = ctx.record() as? UpdatableRecord<*> ?: return
        setIfPresent(record, UPDATED_AT, timeProvider.now())
    }

    private fun setIfPresent(record: UpdatableRecord<*>, column: String, value: Instant) {
        val field = record.fields().firstOrNull { it.name.equals(column, ignoreCase = true) } ?: return
        if (field.type == Instant::class.java) {
            @Suppress("UNCHECKED_CAST")
            record.set(field as org.jooq.Field<Instant>, value)
        }
    }

    companion object {
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }
}
