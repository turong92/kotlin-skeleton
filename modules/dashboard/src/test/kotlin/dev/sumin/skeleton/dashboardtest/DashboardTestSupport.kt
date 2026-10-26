package dev.sumin.skeleton.dashboardtest

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardCohortMember
import dev.sumin.skeleton.common.dashboard.DashboardCohortSource
import dev.sumin.skeleton.common.dashboard.DashboardFunnelStep
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardPoint
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import dev.sumin.skeleton.common.project.ProjectContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.authentication.TestingAuthenticationToken

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class DashboardTestApplication

fun operator(id: String, vararg roles: String) = TestingAuthenticationToken(id, "n/a", *roles.map { "ROLE_$it" }.toTypedArray())

class FakeMetric(
    override val key: String,
    override val order: Int = 0,
    override val unit: DashboardUnit = DashboardUnit.COUNT,
    override val comparable: Boolean = true,
    private val value: () -> DashboardResult<List<DashboardAmount>>,
) : DashboardMetric {
    override val label = key
    override val source = "db"
    val valueCalls = AtomicInteger()

    override fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>> {
        valueCalls.incrementAndGet()
        return value()
    }

    override fun series(range: DashboardRange, interval: DashboardInterval): DashboardResult<List<DashboardPoint>> =
        DashboardResult(DashboardResult.Status.OK, listOf(DashboardPoint(range.from, 3)))
}

fun ok(vararg amounts: DashboardAmount) = DashboardResult(DashboardResult.Status.OK, amounts.toList())

@TestConfiguration(proxyBeanMethods = false)
class DashboardFakes {
    @Bean fun count() = FakeMetric("fake.count", order = 1) { ok(DashboardAmount(7)) }

    @Bean fun money() = FakeMetric("fake.money", order = 2, unit = DashboardUnit.MONEY, comparable = false) {
        ok(DashboardAmount(500, "USD"), DashboardAmount(900, "KRW"), DashboardAmount(1200, "JPY"))
    }

    @Bean fun boom() = FakeMetric("fake.boom", order = 3) { throw IllegalStateException("token=secret-value") }

    @Bean fun pages() = object : DashboardRanking {
        override val key = "fake.pages"
        override val group = "pages"
        override val label = "Pages"
        override val columns = listOf("Page", "Views")
        override val source = "db"
        override val order = 1
        override fun top(range: DashboardRange, limit: Int) =
            DashboardResult(DashboardResult.Status.OK, listOf(DashboardRow("/a", listOf(5)), DashboardRow("/b", listOf(2))).take(limit))
    }

    @Bean fun cohort() = object : DashboardCohortSource {
        override val key = "fake.signed-up"
        override val label = "Signed up"
        override fun members(range: DashboardRange) = (1..4).map { DashboardCohortMember("acc_$it", Instant.EPOCH) }
    }

    @Bean fun returned() = object : DashboardFunnelStep {
        override val key = "fake.returned"
        override val label = "Returned"
        override val order = 1
        override fun reached(cohort: List<DashboardCohortMember>) = 2L
    }
}

/** 프로젝트를 값으로 돌려주는 대역 — 캐시가 프로젝트를 섞으면 다른 프로젝트의 값이 보인다 */
@TestConfiguration(proxyBeanMethods = false)
class ProjectEchoFakes {
    @Bean fun echo() = FakeMetric("fake.project") { ok(DashboardAmount(if (ProjectContext.current().id == "alpha") 111 else 222)) }
}
