package dev.sumin.skeleton.common.project

import org.springframework.boot.autoconfigure.AutoConfiguration
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
}
