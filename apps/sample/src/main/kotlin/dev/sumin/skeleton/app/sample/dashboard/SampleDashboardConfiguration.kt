package dev.sumin.skeleton.app.sample.dashboard

import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 앱 통계 카드 예시 — 앱이 자기 숫자를 대시보드에 붙이는 법: [DashboardRanking] 빈을 두면 끝이다 (`group = "app"` 이 「앱 통계」 카드의 탭이 된다).
 * 여기서는 게시판 모듈이 이미 가진 표를 읽기 전용으로 센다 — 기간 안 게시판별 글 수, 반응 종류별 수. **예시일 뿐이다: 남의 모듈 표를 직접 읽는 것은 정석이 아니다.**
 * 정석은 앱 자기 표(또는 모듈의 서비스)에서 세는 것이다 — 모듈의 표 모양은 모듈이 바꿀 수 있다 (docs/modules/dashboard.md 「앱 통계」).
 */
@Configuration(proxyBeanMethods = false)
class SampleDashboardConfiguration {
    @Bean
    fun boardPostsRanking(jdbc: JdbcClient, dialect: SqlDialect): DashboardRanking = object : DashboardRanking {
        override val key = "app.board-posts"
        override val group = "app"
        override val label = "Posts per board"
        override val columns = listOf("Board", "Posts")
        override val source = "app"
        override val order = 10

        override fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>> {
            val rows = jdbc.sql(
                "select board_code, count(*) as n from board_posts where status = 'PUBLISHED' and created_at >= :from and created_at < :to group by board_code order by n desc, board_code limit :limit",
            ).param("from", dialect.instantParam(range.from)).param("to", dialect.instantParam(range.to)).param("limit", limit)
                .query { rs, _ -> DashboardRow(rs.getString("board_code"), listOf(rs.getLong("n"))) }.list()
            return DashboardResult(DashboardResult.Status.OK, rows)
        }
    }

    @Bean
    fun boardReactionsRanking(jdbc: JdbcClient, dialect: SqlDialect): DashboardRanking = object : DashboardRanking {
        override val key = "app.board-reactions"
        override val group = "app"
        override val label = "Reactions"
        override val columns = listOf("Reaction", "Count")
        override val source = "app"
        override val order = 20

        override fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>> {
            val rows = jdbc.sql(
                "select reaction_type, count(*) as n from board_reactions where created_at >= :from and created_at < :to group by reaction_type order by n desc, reaction_type limit :limit",
            ).param("from", dialect.instantParam(range.from)).param("to", dialect.instantParam(range.to)).param("limit", limit)
                .query { rs, _ -> DashboardRow(rs.getString("reaction_type"), listOf(rs.getLong("n"))) }.list()
            return DashboardResult(DashboardResult.Status.OK, rows)
        }
    }
}
