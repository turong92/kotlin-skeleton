package dev.sumin.skeleton.audit.jdbc

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import java.time.Duration
import java.time.Instant
import org.springframework.boot.context.properties.ConfigurationProperties

/** 저장된 운영 기록 한 줄. [detail] 은 [dev.sumin.skeleton.common.authz.OperatorAction.detail] 그대로 — 호출자가 이미 id 만 싣는다 */
data class AuditEntry(
    val id: Long,
    val occurredAt: Instant,
    val actorId: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val result: String,
    val ip: String?,
    val userAgent: String?,
    val detail: Map<String, String>,
)

/** 조회 조건 — 모두 비면 전부. [from] 은 포함 · [to] 는 미포함, [before] 는 이 id 보다 오래된 줄만(커서), 결과는 id 내림차순(새것부터) 최대 [limit] 줄 */
data class AuditQuery(
    val from: Instant? = null,
    val to: Instant? = null,
    val actorId: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val before: Long? = null,
    val limit: Int = 50,
)

/** 기록의 읽기 · 보관 기한 지우기. 쓰기는 [dev.sumin.skeleton.common.authz.OperatorAuditLog] — 수정 · 임의 삭제 길은 없다 */
interface AuditLogStore {
    fun find(query: AuditQuery): List<AuditEntry>

    /** [cutoff] 보다 오래된 줄을 최대 [limit] 줄 지우고 지운 수를 돌려준다. 데이터베이스가 30일이 안 된 줄은 지우지 못하게 막는다 */
    fun purgeBefore(cutoff: Instant, limit: Int): Int
}

object AuditPermissions {
    /** 운영 기록 보기 (운영자 id · 접속 IP · 기기 정보가 든다) — 기본 역할이 없어 전권만 가진다 */
    const val READ = "audit.read"

    val declaration = PermissionDeclaration("audit", listOf(DeclaredPermission(READ)))
}

/**
 * ```yaml
 * skeleton:
 *   audit:
 *     retention:        # 비워 두면 지우지 않는다(기본). 예: 365d - 이보다 오래된 줄을 매일 지운다. 30일 미만이면 기동 실패
 *     admin:
 *       enabled: false  # true 면 운영자 조회 GET /api/v1/admin/audit-logs (권한 audit.read). 모듈 기본은 꺼짐(404)
 * ```
 */
@ConfigurationProperties("skeleton.audit")
data class AuditProperties(
    /** 기록을 이만큼 보관한 뒤 지운다. **기본 null = 지우지 않는다**(앱이 정한다). 데이터베이스 트리거가 30일 안의 줄은 지우지 못하게 하므로 그보다 짧게는 정할 수 없다 */
    val retention: Duration? = null,
    val admin: Admin = Admin(),
) {
    init {
        require(retention == null || retention >= MIN_RETENTION) {
            "skeleton.audit.retention must be at least ${MIN_RETENTION.toDays()} days when set (the database refuses to delete a line younger than that; leave it unset to keep everything)"
        }
    }

    data class Admin(
        /** 운영자 조회 `GET /api/v1/admin/audit-logs` — 모듈 기본은 꺼짐(404) */
        val enabled: Boolean = false,
    )

    companion object {
        /** 마이그레이션의 트리거가 쓰는 같은 30일 */
        val MIN_RETENTION: Duration = Duration.ofDays(30)
    }
}
