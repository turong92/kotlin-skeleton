package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.audience.AudienceParam
import dev.sumin.skeleton.common.audience.AudienceSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 공지 대상 `workspace` - 열린(`ACTIVE`) 워크스페이스의 **활성 계정** 멤버. 값은 워크스페이스 id. 닫힌 워크스페이스는 [validate] 가 거절하고, 떠난 멤버(줄이 지워진 사람)는 나오지 않는다.
 * 계정 표를 모른다 - 정지 · 삭제 유예 · 대기 계정은 `AccountFacts.activeAmong` 으로 거른다(`account` 가 없으면 [facts] 가 null 이라 거르지 않는다).
 * 키셋: `account_id > after order by account_id limit n` 로 읽어 거르고, 거르고 남은 게 모자라면 거르기 전 쪽의 마지막 id 부터 이어 읽는다 -
 * 한 쪽이 통째로 걸러져도 끝으로 오인하지 않는다(빈 목록은 정말 끝일 때뿐). [count] 는 같은 길로 끝까지 센다.
 */
class JdbcWorkspaceAudience(private val jdbc: NamedParameterJdbcTemplate, private val facts: () -> AccountFacts?) : AudienceSource {
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

    override fun count(param: String?): Long {
        if (facts() == null) {
            return jdbc.queryForObject(
                "select count(*) from workspace_members m join workspaces w on w.id = m.workspace_id where m.workspace_id = :w and w.status = 'ACTIVE'",
                mapOf("w" to param), Long::class.java,
            ) ?: 0L
        }
        var total = 0L
        var after: String? = null
        while (true) {
            val chunk = page(param, after, COUNT_CHUNK)
            if (chunk.isEmpty()) return total
            total += chunk.size
            after = chunk.last()
        }
    }

    override fun page(param: String?, after: String?, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var cursor = after
        while (out.size < limit) {
            val raw = members(param, cursor, limit)
            if (raw.isEmpty()) break
            val keep = facts()?.activeAmong(raw)?.let { active -> raw.filter { it in active } } ?: raw
            out += keep.take(limit - out.size)
            if (raw.size < limit) break
            cursor = raw.last()
        }
        return out
    }

    private fun members(param: String?, after: String?, limit: Int): List<String> =
        jdbc.query(
            "select m.account_id from workspace_members m join workspaces w on w.id = m.workspace_id where m.workspace_id = :w and w.status = 'ACTIVE'" +
                "${if (after != null) " and m.account_id > :after" else ""} order by m.account_id limit :limit",
            mapOf("w" to param, "limit" to limit) + (if (after != null) mapOf("after" to after) else emptyMap()),
        ) { rs, _ -> rs.getString(1) }

    private companion object {
        const val COUNT_CHUNK = 1000
    }
}
