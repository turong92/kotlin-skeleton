package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.common.audience.AudienceParam
import dev.sumin.skeleton.common.audience.AudienceSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 공지 대상 `workspace` - 열린(`ACTIVE`) 워크스페이스의 멤버. 값은 워크스페이스 id. 닫힌 워크스페이스는 [validate] 가 거절하고, 떠난 멤버(줄이 지워진 사람 · 지워진 계정)는 나오지 않는다.
 * 계정 표를 모른다 - 정지 · 삭제 유예 중인 계정의 멤버십은 남아 있어 대상에 든다 (그 계정은 로그인하지 못하니 받은 알림함 줄을 읽을 수 없을 뿐이다).
 * 키셋: `account_id > after order by account_id limit n`.
 */
class JdbcWorkspaceAudience(private val jdbc: NamedParameterJdbcTemplate) : AudienceSource {
    override val kind = "workspace"
    override val param = AudienceParam.WORKSPACE

    override fun validate(param: String?): String? {
        if (param.isNullOrBlank()) return "Give a workspace id."
        val status = jdbc.query("select status from workspaces where id = :id", mapOf("id" to param)) { rs, _ -> rs.getString(1) }.firstOrNull()
        return when (status) {
            null -> "No such workspace."
            "ACTIVE" -> null
            else -> "The workspace is closed."
        }
    }

    override fun count(param: String?): Long =
        jdbc.queryForObject(
            "select count(*) from workspace_members m join workspaces w on w.id = m.workspace_id where m.workspace_id = :w and w.status = 'ACTIVE'",
            mapOf("w" to param), Long::class.java,
        ) ?: 0L

    override fun page(param: String?, after: String?, limit: Int): List<String> =
        jdbc.query(
            "select m.account_id from workspace_members m join workspaces w on w.id = m.workspace_id where m.workspace_id = :w and w.status = 'ACTIVE'" +
                "${if (after != null) " and m.account_id > :after" else ""} order by m.account_id limit :limit",
            mapOf("w" to param, "limit" to limit) + (if (after != null) mapOf("after" to after) else emptyMap()),
        ) { rs, _ -> rs.getString(1) }
}
