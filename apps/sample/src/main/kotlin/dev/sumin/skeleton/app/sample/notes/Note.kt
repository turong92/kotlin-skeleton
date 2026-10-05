package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.persistence.jdbc.AuditTimestamps
import dev.sumin.skeleton.persistence.jdbc.JdbcAuditable
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

enum class NoteStatus { DRAFT, ACTIVE, ARCHIVED }

/**
 * 이 앱의 유일한 애그리거트. persistence-jdbc 의 [JdbcAuditable] 을 구현하면 created_at / updated_at 을 콜백이 채운다.
 * id 는 DB 가 정한다(`gen_random_uuid()`) — 아직 저장 전이면 null 이다.
 */
@Table("notes")
data class Note(
    @Id val id: UUID? = null,
    val ownerId: String,
    val title: String,
    val body: String = "",
    val status: NoteStatus = NoteStatus.DRAFT,
    val pinned: Boolean = false,
    val attachmentKey: String? = null,
    val attachmentName: String? = null,
    @Embedded.Nullable
    override val audit: AuditTimestamps = AuditTimestamps.now(),
) : JdbcAuditable {
    override val isNew: Boolean
        get() = id == null

    override fun withAudit(audit: AuditTimestamps): Note = copy(audit = audit)
}
