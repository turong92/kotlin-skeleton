package dev.sumin.skeleton.dashboard.jdbc

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.dashboard.DashboardLayoutStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** `dashboard_layouts` — 계정마다 한 줄. 저장은 [JdbcDashboardDailyStore] 와 같은 두 문장(없으면 넣고, 덮어쓰기) */
class JdbcDashboardLayoutStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
) : DashboardLayoutStore {
    private val insertIgnore = dialect.insertIgnore("dashboard_layouts", listOf("account_id", "layout", "updated_at"), listOf("account_id"))

    override fun find(accountId: String): String? =
        jdbc.query("select layout from dashboard_layouts where account_id = :id", mapOf("id" to accountId)) { rs, _ -> rs.getString("layout") }.firstOrNull()

    override fun save(accountId: String, json: String) {
        val params = mapOf("account_id" to accountId, "layout" to json, "updated_at" to dialect.instantParam(time.now()))
        tx.executeWithoutResult {
            jdbc.update(insertIgnore, params)
            jdbc.update("update dashboard_layouts set layout = :layout, updated_at = :updated_at where account_id = :account_id", params)
        }
    }

    override fun delete(accountId: String) {
        jdbc.update("delete from dashboard_layouts where account_id = :id", mapOf("id" to accountId))
    }
}

/** 계정이 지워질 때 그 계정의 화면 설정을 지운다 (멱등) */
class DashboardLayoutErasure(private val layouts: DashboardLayoutStore) : AccountErasureListener {
    override val name = "dashboard-jdbc"

    override fun erase(request: ErasureRequest) = layouts.delete(request.accountId)
}
