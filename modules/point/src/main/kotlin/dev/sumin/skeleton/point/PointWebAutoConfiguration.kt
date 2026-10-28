package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.web.PointAdminController
import dev.sumin.skeleton.point.web.PointController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 포인트 HTTP — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 지갑([PointWallet] 빈)이 있을 때만 (없으면 컨트롤러 클래스를 읽지도 않는다 — noOptionalTest 가 증명).
 * 사용자 경로 `/api/v1/points` 는 `skeleton.point.http.enabled=false` 로 끄고, 운영 경로 `/api/v1/admin/points` 는 `skeleton.point.admin.enabled=true` 일 때만 선다.
 */
@AutoConfiguration(after = [PointAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(PointWallet::class)
class PointWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.point.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun pointController(wallet: PointWallet): PointController = PointController(wallet)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.point.admin", name = ["enabled"], havingValue = "true")
    fun pointAdminController(
        wallet: PointWallet,
        access: OperatorAccess,
        audit: OperatorAuditLog,
        time: TimeProvider,
        transactionManager: ObjectProvider<PlatformTransactionManager>,
    ): PointAdminController =
        PointAdminController(wallet, access, audit, time, transactionManager.getIfAvailable()?.let(::TransactionTemplate))
}
