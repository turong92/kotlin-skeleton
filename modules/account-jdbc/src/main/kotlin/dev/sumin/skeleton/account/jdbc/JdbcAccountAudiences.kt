package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.common.audience.AudienceParam
import dev.sumin.skeleton.common.audience.AudienceSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 공지 대상의 기본 둘 — `all`(활성 계정 전체) · `role`(그 역할을 가진 활성 계정). 대기 · 정지 · 삭제 유예 · 지워진 계정은 나오지 않는다.
 * 키셋: `id > after order by id limit n` — 계정 표의 기본키 순서라 쪽마다 새로 읽어도 겹치거나 빠지지 않는다.
 */
object JdbcAccountAudiences {
    fun all(jdbc: NamedParameterJdbcTemplate): AudienceSource = object : AudienceSource {
        override val kind = "all"
        override val param = AudienceParam.NONE
        override fun validate(param: String?) = if (param.isNullOrBlank()) null else "This audience takes no value."
        override fun count(param: String?): Long =
            jdbc.queryForObject("select count(*) from accounts where status = 'ACTIVE'", emptyMap<String, Any>(), Long::class.java) ?: 0L

        override fun page(param: String?, after: String?, limit: Int): List<String> =
            keyset(jdbc, "select id from accounts where status = 'ACTIVE'", "id", emptyMap(), after, limit)
    }

    fun role(jdbc: NamedParameterJdbcTemplate): AudienceSource = object : AudienceSource {
        override val kind = "role"
        override val param = AudienceParam.ROLE
        override fun validate(param: String?) = if (param.isNullOrBlank() || param.length > MAX_ROLE_LENGTH) "Give a role name of 1 to $MAX_ROLE_LENGTH characters." else null
        override fun count(param: String?): Long =
            jdbc.queryForObject(
                "select count(*) from accounts a join account_roles r on r.account_id = a.id where r.role = :role and a.status = 'ACTIVE'",
                mapOf("role" to param), Long::class.java,
            ) ?: 0L

        override fun page(param: String?, after: String?, limit: Int): List<String> =
            keyset(jdbc, "select a.id from accounts a join account_roles r on r.account_id = a.id where r.role = :role and a.status = 'ACTIVE'", "a.id", mapOf("role" to param), after, limit)
    }

    private fun keyset(jdbc: NamedParameterJdbcTemplate, select: String, key: String, params: Map<String, Any?>, after: String?, limit: Int): List<String> =
        jdbc.query(
            "$select${if (after != null) " and $key > :after" else ""} order by $key limit :limit",
            params + mapOf("limit" to limit) + (if (after != null) mapOf("after" to after) else emptyMap()),
        ) { rs, _ -> rs.getString(1) }

    private const val MAX_ROLE_LENGTH = 40   // account_roles.role varchar(40)
}
