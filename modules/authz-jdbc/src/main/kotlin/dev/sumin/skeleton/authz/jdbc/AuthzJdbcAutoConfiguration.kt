package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.authz.AuthzModuleAutoConfiguration
import dev.sumin.skeleton.authz.AuthzRoleStore
import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.AuthzProperties
import dev.sumin.skeleton.common.authz.CachingRolePermissionSource
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RolePermissionSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations

/**
 * 역할 → 권한 표가 진실이 되게 한다: platform 의 yml 구현을 DB 구현(캐시 포함)으로 덮고, `authz` 의 저장소 포트를 JDBC 로 채우고,
 * 처음 한 번 yml 매핑을 표에 옮긴다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261024144150__authz_roles.sql`.
 * 앱이 같은 타입의 빈을 두면 이쪽이 물러난다.
 */
@AutoConfiguration(
    before = [AuthzAutoConfiguration::class, AuthzModuleAutoConfiguration::class],
    after = [JdbcClientAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
)
@ConditionalOnBean(DataSource::class, JdbcClient::class)
class AuthzJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RolePermissionSource::class)
    fun jdbcRolePermissionSource(jdbc: JdbcClient, properties: AuthzProperties): CachingRolePermissionSource =
        CachingRolePermissionSource(JdbcRolePermissionSource(jdbc, properties.superRole), properties.cacheTtl)

    @Bean
    @ConditionalOnMissingBean(AuthzRoleStore::class)
    fun jdbcAuthzRoleStore(
        jdbc: JdbcClient,
        dialect: SqlDialect,
        tx: ObjectProvider<TransactionOperations>,
        time: ObjectProvider<TimeProvider>,
        cache: ObjectProvider<CachingRolePermissionSource>,
    ): AuthzRoleStore = JdbcAuthzRoleStore(
        jdbc, dialect, tx.getIfAvailable { TransactionOperations.withoutTransaction() }, time.getIfAvailable { TimeProvider.systemUtc() },
    ) { cache.ifAvailable?.invalidate() }

    /** 씨앗은 platform 이 yml 로 만드는 매핑(`skeleton.authz.roles` 또는 모듈 선언의 기본 역할)을 그대로 쓴다 */
    @Bean
    @ConditionalOnMissingBean
    fun authzRoleSeeder(
        jdbc: JdbcClient,
        dialect: SqlDialect,
        tx: ObjectProvider<TransactionOperations>,
        time: ObjectProvider<TimeProvider>,
        properties: AuthzProperties,
        declarations: ObjectProvider<PermissionDeclaration>,
    ): AuthzRoleSeeder = AuthzRoleSeeder(
        jdbc, dialect, tx.getIfAvailable { TransactionOperations.withoutTransaction() }, time.getIfAvailable { TimeProvider.systemUtc() },
        AuthzAutoConfiguration().rolePermissionSource(properties, declarations), properties.superRole,
    )
}
