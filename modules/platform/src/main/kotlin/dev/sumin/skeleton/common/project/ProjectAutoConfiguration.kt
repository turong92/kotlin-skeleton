package dev.sumin.skeleton.common.project

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import tools.jackson.databind.ObjectMapper

/** `skeleton.projects` 에 프로젝트가 하나라도 있을 때만 켜진다 — 비어 있으면 이 모듈은 아무것도 등록하지 않는다 */
class ProjectsConfigured : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean = ProjectRegistry.configured(context.environment)
}

@AutoConfiguration
@Conditional(ProjectsConfigured::class)
class ProjectAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun projectRegistry(environment: Environment): ProjectRegistry = ProjectRegistry.from(environment)

    /** `ForwardedHeaderFilter`(HIGHEST+1) 가 Host 를 푼 **뒤**, 보안 헤더 · CORS 앞 */
    @Bean
    @ConditionalOnMissingBean(name = ["projectFilterRegistration"])
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun projectFilterRegistration(registry: ProjectRegistry, objectMapper: ObjectMapper): FilterRegistrationBean<ProjectFilter> =
        FilterRegistrationBean(ProjectFilter(registry, objectMapper)).apply { order = Ordered.HIGHEST_PRECEDENCE + 2 }

    /** 서비스 중인 프로젝트가 둘 이상이면 프로젝트를 모르는 기능이 빌드에 있는지 본다 — 모듈마다 가드를 흩뿌리지 않고 이 한 곳에서 */
    @Bean
    @ConditionalOnMissingBean(name = ["projectUnawareFeaturesCheck"])
    fun projectUnawareFeaturesCheck(registry: ProjectRegistry): SmartInitializingSingleton =
        SmartInitializingSingleton { ProjectUnawareFeatures.check(registry) }

    /** [ProjectApi] 를 읽는 핸들러 매핑 — 목록이 비어 있으면 이 빈이 없어 표시는 무시된다 */
    @Bean
    @ConditionalOnMissingBean(name = ["projectApiMvcRegistrations"])
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun projectApiMvcRegistrations(registry: ProjectRegistry): WebMvcRegistrations = object : WebMvcRegistrations {
        override fun getRequestMappingHandlerMapping(): RequestMappingHandlerMapping = ProjectApiHandlerMapping(registry)
    }
}
