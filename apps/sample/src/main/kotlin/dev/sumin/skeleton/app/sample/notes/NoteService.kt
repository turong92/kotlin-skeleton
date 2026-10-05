package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

data class NotePage(val notes: List<Note>, val totalElements: Long)

/**
 * 노트의 모든 규칙(소유자 확인 · 알림 · 잡 · 첨부 정리)이 여기 있다. 컨트롤러는 HTTP 변환만 한다.
 * 쓰기는 한 트랜잭션 — 알림(받은편지함)과 잡 등록은 노트 저장과 함께 커밋되거나 함께 사라진다.
 */
@Service
class NoteService(
    private val notes: NoteRepository,
    private val notifier: NoteNotifier,
    private val jobs: JobQueue,
    private val storage: ObjectProvider<ObjectStorage>,
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(owner: String, request: CreateNoteRequest): Note {
        val saved = notes.save(
            Note(ownerId = owner, title = request.title.trim(), body = request.body, status = request.status, pinned = request.pinned),
        )
        notifier.created(saved)
        return saved
    }

    @Transactional(readOnly = true)
    fun get(owner: String, id: String): Note = find(owner, id)

    @Transactional(readOnly = true)
    fun list(owner: String, page: PageQuery, q: String?, status: NoteStatus?, pinned: Boolean?): NotePage {
        val pattern = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        return NotePage(
            notes = notes.search(owner, status?.name, pinned, pattern, page.size, page.offset()),
            totalElements = notes.countMatching(owner, status?.name, pinned, pattern),
        )
    }

    @Transactional
    fun replace(owner: String, id: String, request: ReplaceNoteRequest): Note {
        val current = find(owner, id)
        val key = request.attachmentKey
        val saved = notes.save(
            current.copy(
                title = request.title.trim(),
                body = request.body,
                status = request.status,
                pinned = request.pinned,
                attachmentKey = key,
                attachmentName = key?.let { request.attachmentName?.takeIf { name -> name.isNotBlank() } ?: it.substringAfterLast('/') },
            ),
        )
        notifier.updated(saved)
        if (current.attachmentKey != null && current.attachmentKey != key) discardAfterCommit(current.attachmentKey)
        return saved
    }

    @Transactional
    fun delete(owner: String, id: String) {
        val current = find(owner, id)
        notes.delete(current)
        notifier.deleted(current)
        current.attachmentKey?.let(::discardAfterCommit)
    }

    @Transactional(readOnly = true)
    fun summary(owner: String): NoteSummaryResponse =
        jdbc.sql(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE pinned) AS pinned,
                   count(*) FILTER (WHERE attachment_key IS NOT NULL) AS with_attachment,
                   count(*) FILTER (WHERE status = 'DRAFT') AS draft,
                   count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                   count(*) FILTER (WHERE status = 'ARCHIVED') AS archived
            FROM notes WHERE owner_id = :owner
            """,
        ).param("owner", owner).query { rs, _ ->
            NoteSummaryResponse(rs.getLong("total"), rs.getLong("pinned"), rs.getLong("with_attachment"), rs.getLong("draft"), rs.getLong("active"), rs.getLong("archived"))
        }.single()

    /** 내보내기는 오래 걸릴 수 있어 잡으로 넘기고 바로 돌려준다(202). 끝나면 [NoteExportJobHandler] 가 알린다 */
    @Transactional
    fun startExport(owner: String, id: String): Long {
        val note = find(owner, id)
        val payload = json.writeValueAsString(mapOf("noteId" to note.id.toString(), "ownerId" to owner))
        return jobs.enqueue(NoteExportJobHandler.TYPE, payload)
    }

    private fun find(owner: String, id: String): Note {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: throw NoteNotFoundException(id)
        return notes.findByIdAndOwnerId(uuid, owner) ?: throw NoteNotFoundException(id)
    }

    /** 첨부 파일은 DB 가 커밋된 뒤에 지운다 — 롤백되면 파일은 그대로여야 한다. 실패해도 노트 변경은 되돌리지 않는다 */
    private fun discardAfterCommit(key: String) {
        val objectStorage = storage.getIfAvailable() ?: return
        val delete = {
            runCatching { objectStorage.delete(ObjectKey(key)) }.onFailure { log.warn("attachment cleanup failed: {}", key, it) }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { delete() }
            })
        } else {
            delete()
        }
    }
}
