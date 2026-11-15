package dev.sumin.skeleton.audit.jdbc

import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.PeriodicRunCheck
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 운영 기록([OperatorAuditLog])을 JDBC(PostgreSQL · MySQL)로 구현한다 — platform 의 아무것도 안 하는 기본을 대신한다(그래서 그쪽 자동 설정보다 **먼저** 평가한다).
 * 앱이 같은 타입의 빈을 두면 이쪽은 물러난다(그때는 조회 · 보관 기한도 없다). 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261116130000__operator_audit_log.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. SqlDialect 는 조건에 넣지 않는다(방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [AuthzAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
@EnableConfigurationProperties(AuditProperties::class)
class AuditJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(OperatorAuditLog::class)
    fun jdbcOperatorAuditLog(dataSource: DataSource, dialect: SqlDialect, time: ObjectProvider<TimeProvider>, origin: ObjectProvider<AuditOrigin>): JdbcOperatorAuditLog =
        JdbcOperatorAuditLog(NamedParameterJdbcTemplate(dataSource), dialect, time.getIfAvailable { TimeProvider.systemUtc() }, origin.getIfAvailable { AuditOrigin.NONE })

    @Bean
    @ConditionalOnMissingBean(name = ["auditPermissionDeclaration"])
    fun auditPermissionDeclaration(): PermissionDeclaration = AuditPermissions.declaration

    /** 보관 기한을 정했을 때만 - 기본 꺼짐(값을 비운 `retention:` 도 꺼짐). 돌릴 길(scheduler · 요청 구동)이 없으면 조용히 안 지우는 대신 기동 실패 */
    @Bean
    @ConditionalOnBean(AuditLogStore::class)
    @ConditionalOnExpression("'\${skeleton.audit.retention:}'.trim() != ''")
    @ConditionalOnMissingBean
    fun auditRetention(store: AuditLogStore, properties: AuditProperties, time: ObjectProvider<TimeProvider>, environment: Environment): AuditRetention {
        PeriodicRunCheck.require("skeleton.audit.retention", environment)
        return AuditRetention(store, checkNotNull(properties.retention), time.getIfAvailable { TimeProvider.systemUtc() })
    }

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - 지우기가 멱등이다 */
    @Bean
    @ConditionalOnBean(AuditRetention::class)
    @ConditionalOnMissingBean(name = ["auditRetentionTask"])
    fun auditRetentionTask(retention: AuditRetention): OnRequestTask = SimpleOnRequestTask("audit-retention") { retention.purge() }
}
