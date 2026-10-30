package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.persistence.jdbc.AuditTimestamps
import dev.sumin.skeleton.persistence.jdbc.JdbcAuditable
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

enum class NoteStatus { DRAFT, ACTIVE, ARCHIVED }

/**
 * 이 앱의 유일한 애그리거트. persistence-jdbc 의 [JdbcAuditable] 을 구현하면 created_at / updated_at 을 콜백이 채운다.
 * id 는 앱이 만든다(시간순 UUIDv7 — `SortableIds.newUuid()`, 열 타입은 `uuid` 그대로). id 가 이미 있어 Spring Data 는 새 줄인지 모르므로 만들 때는 `JdbcAggregateOperations.insert` 로 넣고, 감사 콜백에는 [fresh] 로 알린다.
 */
@Table("notes")
data class Note(
    @Id val id: UUID,
    /** 쓴 사람. [workspaceId] 가 없으면 곧 주인(내 노트) */
    val ownerId: String,
    /** 이 노트가 속한 워크스페이스. null 이면 내 노트 - 워크스페이스 표는 모듈의 것이라 외래 키는 없고, 접근은 요청마다 멤버십으로 판정한다 */
    val workspaceId: String? = null,
    val title: String,
    val body: String = "",
    val status: NoteStatus = NoteStatus.DRAFT,
    val pinned: Boolean = false,
    val attachmentKey: String? = null,
    val attachmentName: String? = null,
    @Embedded.Nullable
    override val audit: AuditTimestamps = AuditTimestamps.now(),
    /** 아직 저장하지 않은 새 줄이다. DB 에서 읽은 줄은 항상 false(저장 컬럼이 아니라 기본값이 쓰인다). */
    @Transient val fresh: Boolean = false,
) : JdbcAuditable {
    override val isNew: Boolean
        get() = fresh

    override fun withAudit(audit: AuditTimestamps): Note = copy(audit = audit)
}
