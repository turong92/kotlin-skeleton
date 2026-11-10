package dev.sumin.skeleton.app.nativecheck

import org.jooq.DSLContext
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** persistence-jooq 의 네이티브 시험 경로 — jOOQ 로 쓰기(audit 리스너) · 읽기 · 고치기 · 값 클래스로 읽기. 제품 코드가 아니다. */
@RegisterReflectionForBinding(ProbeNote::class)   // fetchInto(값 클래스)는 리플렉션으로 만든다 - AOT 가 못 찾으니 앱도 이렇게 표시한다 (docs/native-image.md "jOOQ")
@RestController
@RequestMapping("/api/v1/native-check/jooq")
class JooqProbeController(private val dsl: DSLContext) {
    @PostMapping
    fun create(@RequestBody body: PingPayload): JooqProbeView {
        dsl.execute("create table if not exists native_jooq_probe (id bigint generated always as identity primary key, note varchar(200) not null, created_at timestamptz not null, updated_at timestamptz not null)")
        val record = dsl.newRecord(ProbeTable).also { it.note = body.note }
        record.store()
        return view(record)
    }

    @GetMapping("/{id}")
    fun read(@PathVariable id: Long): JooqProbeView? {
        val record = dsl.selectFrom(ProbeTable).where(ProbeTable.ID.eq(id)).fetchOne() ?: return null
        val pojo = dsl.select(ProbeTable.ID, ProbeTable.NOTE).from(ProbeTable).where(ProbeTable.ID.eq(id)).fetchOneInto(ProbeNote::class.java)
        return view(record).copy(viaPojo = pojo?.note)
    }

    @PostMapping("/{id}/touch")
    fun touch(@PathVariable id: Long, @RequestBody body: PingPayload): JooqProbeView? {
        val record = dsl.selectFrom(ProbeTable).where(ProbeTable.ID.eq(id)).fetchOne() ?: return null
        record.note = body.note
        record.store()
        return view(record)
    }

    private fun view(record: ProbeRecord) = JooqProbeView(record.id, record.note, record.createdAt?.toString(), record.updatedAt?.toString(), null)
}

data class JooqProbeView(val id: Long?, val note: String?, val createdAt: String?, val updatedAt: String?, val viaPojo: String?)
