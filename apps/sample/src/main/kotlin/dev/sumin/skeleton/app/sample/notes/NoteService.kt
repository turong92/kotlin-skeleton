package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.campaign.CampaignSignals
import dev.sumin.skeleton.common.PageQuery
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.workspace.WorkspaceErrorCode
import dev.sumin.skeleton.workspace.WorkspaceException
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
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
 * 누구의 노트 상자를 여는가 - [workspace] 가 없으면 [caller] 의 내 노트, 있으면 그 워크스페이스의 노트(호출자의 멤버십은 컨트롤러가 요청마다 확인해 넘긴다).
 * 두 상자는 섞이지 않는다: 내 노트 목록에 팀 노트가 없고, 헤더 없이는 내가 쓴 팀 노트도 안 보인다.
 */
data class NoteScope(val caller: String, val workspace: WorkspaceMember? = null) {
    val workspaceId: String? get() = workspace?.workspaceId
}

/**
 * 노트의 모든 규칙(소유자 확인 · 워크스페이스 권한 · 알림 · 잡 · 첨부 정리)이 여기 있다. 컨트롤러는 HTTP 변환만 한다.
 * 팀 노트는 멤버 모두 읽고 쓰고, 지우기는 쓴 사람 · ADMIN 이상이다(첨부는 아직 내 노트만 - 첨부 키 검사가 계정 기준이다).
 * 쓰기는 한 트랜잭션 — 알림(받은편지함)과 잡 등록은 노트 저장과 함께 커밋되거나 함께 사라진다.
 */
@Service
class NoteService(
    private val notes: NoteRepository,
    private val aggregates: org.springframework.data.jdbc.core.JdbcAggregateOperations,
    private val notifier: NoteNotifier,
    private val jobs: JobQueue,
    private val storage: ObjectProvider<ObjectStorage>,
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val signals: ObjectProvider<CampaignSignals>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 업적 이벤트(`achievement`)의 `metric` - 씨앗 [dev.sumin.skeleton.app.sample.badges.BadgesSeed] 가 이 이름으로 이벤트를 만든다 */
        const val NOTES_CREATED = "notes.created"
    }

    @Transactional
    fun create(scope: NoteScope, request: CreateNoteRequest): Note {
        val owner = scope.caller
        val saved = aggregates.insert(
            Note(id = SortableIds.system.newUuid(), fresh = true, ownerId = owner, workspaceId = scope.workspaceId, title = request.title.trim(), body = request.body, status = request.status, pinned = request.pinned),
        )
        notifier.created(saved)
        // 업적 수치: 저장 뒤 자기 개수를 세어 현재 값을 보고한다(같은 트랜잭션 - 노트가 롤백되면 보고도 사라진다). 이벤트가 없으면 수치만 쌓인다
        if (scope.workspace == null) signals.ifAvailable { it.report(owner, NOTES_CREATED, notes.countMatching(owner, null, null, null, null)) }
        return saved
    }

    @Transactional(readOnly = true)
    fun get(scope: NoteScope, id: String): Note = find(scope, id)

    @Transactional(readOnly = true)
    fun list(scope: NoteScope, page: PageQuery, q: String?, status: NoteStatus?, pinned: Boolean?): NotePage {
        val pattern = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        return NotePage(
            notes = notes.search(scope.caller, scope.workspaceId, status?.name, pinned, pattern, page.size, page.offset()),
            totalElements = notes.countMatching(scope.caller, scope.workspaceId, status?.name, pinned, pattern),
        )
    }

    @Transactional
    fun replace(scope: NoteScope, id: String, request: ReplaceNoteRequest): Note {
        val current = find(scope, id)
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
    fun delete(scope: NoteScope, id: String) {
        val current = find(scope, id)
        val member = scope.workspace
        if (member != null && current.ownerId != scope.caller && !member.role.atLeast(WorkspaceRole.ADMIN)) throw WorkspaceException(WorkspaceErrorCode.FORBIDDEN)
        notes.delete(current)
        notifier.deleted(current)
        current.attachmentKey?.let(::discardAfterCommit)
    }

    @Transactional(readOnly = true)
    fun summary(scope: NoteScope): NoteSummaryResponse =
        jdbc.sql(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE pinned) AS pinned,
                   count(*) FILTER (WHERE attachment_key IS NOT NULL) AS with_attachment,
                   count(*) FILTER (WHERE status = 'DRAFT') AS draft,
                   count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                   count(*) FILTER (WHERE status = 'ARCHIVED') AS archived
            FROM notes
            WHERE ((CAST(:ws AS text) IS NULL AND owner_id = :owner AND workspace_id IS NULL) OR workspace_id = CAST(:ws AS text))
            """,
        ).param("owner", scope.caller).param("ws", scope.workspaceId).query { rs, _ ->
            NoteSummaryResponse(rs.getLong("total"), rs.getLong("pinned"), rs.getLong("with_attachment"), rs.getLong("draft"), rs.getLong("active"), rs.getLong("archived"))
        }.single()

    /** 내보내기는 오래 걸릴 수 있어 잡으로 넘기고 바로 돌려준다(202). 끝나면 [NoteExportJobHandler] 가 알린다 */
    @Transactional
    fun startExport(scope: NoteScope, id: String): Long {
        val owner = scope.caller
        val note = find(scope, id)
        val payload = json.writeValueAsString(mapOf("noteId" to note.id.toString(), "ownerId" to owner))
        return jobs.enqueue(NoteExportJobHandler.TYPE, payload)
    }

    private fun find(scope: NoteScope, id: String): Note {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: throw NoteNotFoundException(id)
        val workspaceId = scope.workspaceId
        val found = if (workspaceId != null) notes.findByIdAndWorkspaceId(uuid, workspaceId) else notes.findByIdAndOwnerIdAndWorkspaceIdIsNull(uuid, scope.caller)
        return found ?: throw NoteNotFoundException(id)
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
