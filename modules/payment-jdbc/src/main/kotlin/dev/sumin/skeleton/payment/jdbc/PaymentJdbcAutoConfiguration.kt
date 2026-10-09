package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentTaskRunner
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * 결제 장부의 저장 포트([PaymentLedgerStore])와 작업 포트([PaymentTasks])를 JDBC(PostgreSQL · MySQL)로 구현한다. 이 빈이 있어야 `payment` 의 장부가 켜진다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261009124513__payment_ledger.sql`(표 네 개)을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다
 * (Flyway 를 안 쓰면 그 SQL 을 schema.sql 에 복사 — `create table if not exists` + 표 안의 인덱스라 두 번 돌아도 된다).
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.payment.jdbc.PaymentJdbcAutoConfiguration`)을 `payment` 의 `PaymentLedgerAutoConfiguration` · `PaymentAlertAutoConfiguration` 이 문자열로 가리킨다** —
 * 그쪽은 저장소 빈이 있어야 켜지므로 이 설정보다 뒤에 서야 한다 — 순서는 그쪽의 `afterName` 한 곳에만 선언한다(여기에도 `before` 를 적으면 그쪽 문자열이 틀려도 가려져 시험이 못 잡는다).
 * 이름을 바꾸면 순서가 풀려 장부가 **조용히** 꺼진다(`PaymentJdbcAutoConfigurationTest` 가 잡는다).
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 — 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class PaymentJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PaymentLedgerStore::class)
    fun jdbcPaymentLedgerStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): PaymentLedgerStore =
        JdbcPaymentLedgerStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect)

    /** 큐(`job-queue-jdbc`)가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다 — 뒷일 없이 돈을 받을 수 없다. */
    @Bean
    @ConditionalOnMissingBean(PaymentTasks::class)
    fun jdbcPaymentTasks(queue: JobQueue, store: PaymentLedgerStore): PaymentTasks = JdbcPaymentTasks(queue, store)

    /**
     * 죽은 작업의 고리 — 큐 워커가 `JobDeadListener` 빈을 모아 DEAD 가 되는 모든 작업에 부른다(`alert` 의 `JOB_DEAD` 경보와 나란히). 이 빈이 우리 작업이면 종류의 처리기가 줄에 표시를 찍는다.
     * 타입을 [PaymentJobDeadListener] 로 적어 다른 `JobDeadListener` 빈이 있어도 비켜서지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean(PaymentJobDeadListener::class)
    fun paymentJobDeadListener(runner: ObjectProvider<PaymentTaskRunner>): PaymentJobDeadListener = PaymentJobDeadListener { runner.getIfAvailable() }

    @Bean
    fun paymentJobHandlerCoverage(runner: ObjectProvider<PaymentTaskRunner>, handlers: ObjectProvider<JobHandler>): SmartInitializingSingleton =
        PaymentJobHandlerCoverage(runner, handlers)

    companion object {
        /** 정적 `@Bean` — 빈 정의를 더하는 후처리기라 설정 클래스 인스턴스보다 먼저 만들어져야 한다. */
        @JvmStatic
        @Bean
        fun paymentJobHandlerRegistrar(): BeanDefinitionRegistryPostProcessor = PaymentJobHandlerRegistrar()
    }
}
