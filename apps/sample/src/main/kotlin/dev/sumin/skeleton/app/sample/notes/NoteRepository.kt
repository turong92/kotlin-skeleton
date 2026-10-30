package dev.sumin.skeleton.app.sample.notes

import java.util.UUID
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

/**
 * 목록 검색은 `:param IS NULL OR …` 한 쿼리로 — 조건이 늘어도 쿼리 조합 코드가 없다. PostgreSQL 은 NULL 의 타입을 몰라 CAST 가 필요하다.
 * 상자는 둘 중 하나다: `ws` 가 null 이면 내 노트(`workspace_id` 없는 것 중 `owner` 의 것), 있으면 그 워크스페이스의 노트(`owner` 는 보지 않는다 - 멤버십은 호출자가 이미 확인했다).
 * `q` 는 호출자(서비스)가 LIKE 패턴(`%…%`, `%` · `_` · `\` 이스케이프)으로 만들어 넘긴다.
 */
interface NoteRepository : CrudRepository<Note, UUID> {
    /** 내 노트(워크스페이스에 속하지 않은 것)만 */
    fun findByIdAndOwnerIdAndWorkspaceIdIsNull(id: UUID, ownerId: String): Note?

    fun findByIdAndWorkspaceId(id: UUID, workspaceId: String): Note?

    @Query(
        """
        SELECT * FROM notes
        WHERE ((CAST(:ws AS text) IS NULL AND owner_id = :owner AND workspace_id IS NULL) OR workspace_id = CAST(:ws AS text))
          AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
          AND (CAST(:pinned AS boolean) IS NULL OR pinned = CAST(:pinned AS boolean))
          AND (CAST(:pattern AS text) IS NULL
               OR title ILIKE CAST(:pattern AS text) ESCAPE '\'
               OR body ILIKE CAST(:pattern AS text) ESCAPE '\')
        ORDER BY pinned DESC, updated_at DESC, id
        LIMIT :limit OFFSET :offset
        """,
    )
    fun search(
        @Param("owner") owner: String,
        @Param("ws") workspaceId: String?,
        @Param("status") status: String?,
        @Param("pinned") pinned: Boolean?,
        @Param("pattern") pattern: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<Note>

    @Query(
        """
        SELECT count(*) FROM notes
        WHERE ((CAST(:ws AS text) IS NULL AND owner_id = :owner AND workspace_id IS NULL) OR workspace_id = CAST(:ws AS text))
          AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
          AND (CAST(:pinned AS boolean) IS NULL OR pinned = CAST(:pinned AS boolean))
          AND (CAST(:pattern AS text) IS NULL
               OR title ILIKE CAST(:pattern AS text) ESCAPE '\'
               OR body ILIKE CAST(:pattern AS text) ESCAPE '\')
        """,
    )
    fun countMatching(
        @Param("owner") owner: String,
        @Param("ws") workspaceId: String?,
        @Param("status") status: String?,
        @Param("pinned") pinned: Boolean?,
        @Param("pattern") pattern: String?,
    ): Long
}
