package dev.sumin.skeleton.app.nativecheck

import java.time.Instant
import org.jooq.TableField
import org.jooq.UniqueKey
import org.jooq.impl.DSL
import org.jooq.impl.Internal
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.jooq.impl.UpdatableRecordImpl

/**
 * jOOQ 네이티브 시험용 표 — 코드 생성기가 만드는 것과 같은 모양(TableImpl + UpdatableRecordImpl)을 손으로 쓴 것이다.
 * native-check 에는 코드 생성이 없어서다. `created_at` · `updated_at` 이 `Instant` 라 persistence-jooq 의 audit 리스너가 채운다.
 * 표는 `JooqProbeController` 가 처음 쓸 때 만든다(마이그레이션 파일을 두지 않는다 - 시험 전용).
 */
object ProbeTable : TableImpl<ProbeRecord>(DSL.name("native_jooq_probe")) {
    val ID: TableField<ProbeRecord, Long> = createField(DSL.name("id"), SQLDataType.BIGINT.nullable(false).identity(true), this, "")
    val NOTE: TableField<ProbeRecord, String> = createField(DSL.name("note"), SQLDataType.VARCHAR(200).nullable(false), this, "")
    val CREATED_AT: TableField<ProbeRecord, Instant> = createField(DSL.name("created_at"), SQLDataType.INSTANT.nullable(false), this, "")
    val UPDATED_AT: TableField<ProbeRecord, Instant> = createField(DSL.name("updated_at"), SQLDataType.INSTANT.nullable(false), this, "")

    private val pkey: UniqueKey<ProbeRecord> by lazy { Internal.createUniqueKey(this, DSL.name("native_jooq_probe_pkey"), arrayOf(ID), true) }

    override fun getRecordType(): Class<ProbeRecord> = ProbeRecord::class.java

    override fun getPrimaryKey(): UniqueKey<ProbeRecord> = pkey

    override fun getIdentity() = Internal.createIdentity(this, ID)
}

class ProbeRecord : UpdatableRecordImpl<ProbeRecord>(ProbeTable) {
    var id: Long?
        get() = get(ProbeTable.ID)
        set(value) { set(ProbeTable.ID, value) }
    var note: String?
        get() = get(ProbeTable.NOTE)
        set(value) { set(ProbeTable.NOTE, value) }
    var createdAt: Instant?
        get() = get(ProbeTable.CREATED_AT)
        set(value) { set(ProbeTable.CREATED_AT, value) }
    var updatedAt: Instant?
        get() = get(ProbeTable.UPDATED_AT)
        set(value) { set(ProbeTable.UPDATED_AT, value) }
}

/** `fetchInto(클래스)` — 레코드를 값 클래스로 옮기는 경로(리플렉션) */
data class ProbeNote(val id: Long, val note: String)

