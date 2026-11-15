package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.appcontrol.web.AppConfigController
import dev.sumin.skeleton.common.authz.NoOpOperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper

/**
 * 기능 스위치 · 점검 모드 · 모바일 최소 버전. **모듈 기본은 꺼짐** - `skeleton.app-control.enabled=true` 일 때만 선다. 저장소(`AppControlStore`)는 `app-control-jdbc` 가 낸다 -
 * 켰는데 없으면 **시작을 멈추고** 무엇을 하면 되는지 말한다. 점검 필터는 켜면 항상 서 있다(점검 상태가 꺼져 있으면 그냥 지나간다).
 * **이 클래스가 `app-control-jdbc` 의 `AppControlJdbcAutoConfiguration` 이름을 문자열로 가리킨다 - 이름을 바꾸지 않는다.**
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.appcontrol.jdbc.AppControlJdbcAutoConfiguration"])
@EnableConfigurationProperties(AppControlProperties::class)
@ConditionalOnProperty(prefix = "skeleton.app-control", name = ["enabled"], havingValue = "true")
class AppControlAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["appControlPermissionDeclaration"])
    fun appControlPermissionDeclaration(): PermissionDeclaration = AppControlPermissions.declaration()

    @Bean
    fun appControlSetupCheck(store: ObjectProvider<AppControlStore>, properties: AppControlProperties): AppControlSetupCheck {
        check(store.getIfAvailable() != null) {
            "skeleton.app-control.enabled=true needs an AppControlStore - add the app-control-jdbc module " +
                "(implementation(project(\":modules:app-control-jdbc\"))) and its migrations"
        }
        check(!properties.cacheTtl.isNegative) { "skeleton.app-control.cache-ttl cannot be negative" }
        return AppControlSetupCheck
    }

    @Bean
    @ConditionalOnMissingBean
    fun appControl(
        @Suppress("UNUSED_PARAMETER") setup: AppControlSetupCheck,
        store: AppControlStore,
        audit: ObjectProvider<OperatorAuditLog>,
        time: ObjectProvider<TimeProvider>,
        properties: AppControlProperties,
        objectMapper: ObjectMapper,
    ): AppControl = AppControl(store, audit.getIfAvailable { NoOpOperatorAuditLog() }, time.getIfAvailable { TimeProvider.systemUtc() }, properties, objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun featureFlags(control: AppControl): FeatureFlags = FeatureFlags(control)

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = ["requireFeatureWebMvcConfigurer"])
    fun requireFeatureWebMvcConfigurer(flags: FeatureFlags): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(RequireFeatureInterceptor(flags))
        }
    }

    /** 순서: 추적 · CORS · 요청 한도(`HIGHEST_PRECEDENCE + 40`) 다음, 보안 체인 앞 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = ["maintenanceFilterRegistration"])
    fun maintenanceFilterRegistration(
        control: AppControl,
        time: ObjectProvider<TimeProvider>,
        properties: AppControlProperties,
        objectMapper: ObjectMapper,
    ): FilterRegistrationBean<MaintenanceFilter> {
        // 이 모듈이 여는 경로는 점검 중에도 열려 있어야 한다 (클라이언트가 점검을 알아내고 · 운영자가 끈다) - 설정으로 경로를 옮겨도 따라간다
        val own = listOfNotNull(
            properties.config.basePath.takeIf { properties.config.enabled },
            properties.admin.basePath.takeIf { properties.admin.enabled }?.let { "$it/**" },
        )
        val filter = MaintenanceFilter(control, time.getIfAvailable { TimeProvider.systemUtc() }, (properties.maintenance.passPaths + own).distinct(), properties.maintenance.retryAfter, objectMapper)
        return FilterRegistrationBean(filter).apply { order = Ordered.HIGHEST_PRECEDENCE + 50 }
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "skeleton.app-control.config", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun appConfigController(control: AppControl, time: ObjectProvider<TimeProvider>): AppConfigController =
        AppConfigController(control, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "skeleton.app-control.config", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["appConfigPublicEndpointContributor"])
    fun appConfigPublicEndpointContributor(properties: AppControlProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("GET", properties.config.basePath) }
}

/** 설정 검사가 끝났다는 표지 - 이 빈을 받는 빈은 검사를 먼저 거친다 */
object AppControlSetupCheck
