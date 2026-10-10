package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentTaskHandler
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskRunner
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager

/**
 * 진짜 자동설정 순서 시험 — `payment` 의 `PaymentLedgerAutoConfiguration` 은 `PaymentLedgerStore` 빈이 있어야 켜지고, 이름 문자열
 * (`dev.sumin.skeleton.payment.jdbc.PaymentJdbcAutoConfiguration`)로 이 모듈 뒤에 선다. 이름이 어긋나면 장부가 **조용히** 꺼진다 — 그것을 여기서 잡는다.
 * 클래스를 일부러 거꾸로 나열해도(`AutoConfigurations` 가 순서 선언으로 다시 정렬한다) `PaymentOrders` 가 떠야 한다.
 */
class PaymentJdbcAutoConfigurationTest {
    private val dataSource: DataSource = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build()

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                PaymentLedgerAutoConfiguration::class.java, // 일부러 먼저 적는다
                PaymentAutoConfiguration::class.java,
                PaymentJdbcAutoConfiguration::class.java,
                JobQueueJdbcAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("skeleton.job-queue.worker-enabled=false")
        .withBean(DataSource::class.java, { dataSource })
        .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(dataSource) })
        .withBean(SqlDialect::class.java, { FakeDialect })
        .withBean(JdbcClient::class.java, { JdbcClient.create(dataSource) })
        .withBean(PaymentCatalog::class.java, { CheerCatalog() })

    @Configuration(proxyBeanMethods = false)
    class AppHandler {
        /** 앱이 더한 작업 종류 — 정적으로 아는 종류(`payment` 의 셋)에 없다. */
        @Bean
        fun appHandler(): AppTaskHandler = AppTaskHandler()
    }

    class AppTaskHandler : PaymentTaskHandler {
        override val type: String = "payment-app-extra"
        override fun handle(run: PaymentTaskRun) = Unit
    }

    @Test
    fun `the ledger comes up in the real auto-configuration order - the order service exists on the JDBC store and the queue`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertIs<JdbcPaymentLedgerStore>(ctx.getBean(PaymentLedgerStore::class.java))
            assertIs<JdbcPaymentTasks>(ctx.getBean(PaymentTasks::class.java))
            assertEquals(1, ctx.getBeansOfType(PaymentOrders::class.java).size, "PaymentLedgerAutoConfiguration ran after PaymentJdbcAutoConfiguration")
        }
    }

    @Test
    fun `the ledger and alert auto-configurations of payment wait for exactly this class by name`() {
        // 자동설정 정렬은 선언이 없을 때 이름순으로 우연히 맞아 버릴 수 있다 — 그래서 위 시험만으로는 payment 쪽 문자열이 틀려도 통과한다. 선언 자체를 읽어 이름을 대조한다
        // (alert 모듈이 이 시험의 클래스패스에 없어도 읽도록 클래스를 로드하지 않고 메타데이터로 읽는다)
        val mine = PaymentJdbcAutoConfiguration::class.java.name
        listOf("dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration", "dev.sumin.skeleton.payment.alert.PaymentAlertAutoConfiguration").forEach { waiting ->
            val metadata = org.springframework.core.type.classreading.SimpleMetadataReaderFactory().getMetadataReader(waiting).annotationMetadata
            val attributes = metadata.getAnnotationAttributes("org.springframework.boot.autoconfigure.AutoConfiguration")!!
            assertThat(attributes["afterName"] as Array<*>).`as`("$waiting afterName").contains(mine)
        }
    }

    @Test
    fun `every task type of the runner gets a queue handler - the ones the app adds included`() {
        runner.withUserConfiguration(AppHandler::class.java).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val queueTypes = ctx.getBeansOfType(JobHandler::class.java).values.map { it.type }
            val runnerTypes = ctx.getBean(PaymentTaskRunner::class.java).types

            assertThat(runnerTypes).contains("payment-app-extra", PaymentTaskTypes.ORDER_EXPIRE, PaymentTaskTypes.ORDER_RECONCILE, PaymentTaskTypes.ORDER_FULFIL)
            assertThat(queueTypes).containsExactlyInAnyOrderElementsOf(runnerTypes)
            assertThat(runnerTypes).containsAll(listOf(PaymentTaskTypes.REFUND_RECONCILE, PaymentTaskTypes.REFUND_APPLY, PaymentTaskTypes.WEBHOOK))
        }
    }

    @Test
    fun `a runner that knows a type no handler bean backs stops the start - the job would sit in the queue until it is dead`() {
        val orphanRunner = object : PaymentTaskRunner {
            override val types = setOf("payment-orphan")
            override fun run(run: PaymentTaskRun) = Unit
        }
        runner.withBean("orphanRunner", PaymentTaskRunner::class.java, { orphanRunner }).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).hasStackTraceContaining("payment-orphan")
        }
    }

    @Test
    fun `an app queue handler for a payment task type that is already handled stops the start - the worker would pick one silently`() {
        val duplicate = object : JobHandler {
            override val type = PaymentTaskTypes.ORDER_EXPIRE
            override fun handle(job: dev.sumin.skeleton.jobqueue.jdbc.Job) = Unit
        }
        runner.withBean("ownExpireHandler", JobHandler::class.java, { duplicate }).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).hasStackTraceContaining(PaymentTaskTypes.ORDER_EXPIRE).hasStackTraceContaining("more than one")
        }
    }

    @Test
    fun `the queue is needed - a disabled job queue stops the start and names the missing bean`() {
        runner.withPropertyValues("skeleton.job-queue.enabled=false").run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).hasStackTraceContaining(JobQueue::class.java.simpleName)
        }
    }
}

private object FakeDialect : SqlDialect {
    override val vendor = "h2"
    override fun instantParam(value: java.time.Instant?): Any? = value
    override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
}
