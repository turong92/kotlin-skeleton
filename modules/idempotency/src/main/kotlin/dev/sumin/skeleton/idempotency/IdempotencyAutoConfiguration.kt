package dev.sumin.skeleton.idempotency

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper

@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.idempotency",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class IdempotencyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun idempotencyStore(): IdempotencyStore =
        InMemoryIdempotencyStore()

    @Bean
    @ConditionalOnMissingBean
    fun idempotencyScopeResolver(): IdempotencyScopeResolver =
        PrincipalIdempotencyScopeResolver()

    @Bean
    @ConditionalOnMissingBean
    fun idempotencyHandlerInterceptor(
        properties: IdempotencyProperties,
        store: IdempotencyStore,
        scopeResolver: IdempotencyScopeResolver,
        objectMapper: ObjectMapper,
    ): IdempotencyHandlerInterceptor =
        IdempotencyHandlerInterceptor(
            properties = properties,
            store = store,
            scopeResolver = scopeResolver,
            objectMapper = objectMapper,
        )

    @Bean
    @ConditionalOnMissingBean(name = ["idempotencyCachingFilterRegistration"])
    fun idempotencyCachingFilterRegistration(
        properties: IdempotencyProperties,
        store: IdempotencyStore,
    ): FilterRegistrationBean<IdempotencyCachingFilter> =
        FilterRegistrationBean(IdempotencyCachingFilter(properties, store)).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 60
        }

    @Bean
    fun idempotencyWebMvcConfigurer(
        interceptor: IdempotencyHandlerInterceptor,
    ): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(interceptor)
            }
        }

    @Bean
    fun idempotencyOperationCustomizer(): GlobalOperationCustomizer =
        GlobalOperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.hasMethodAnnotation(IdempotentOperation::class.java)) {
                operation.addIdempotencyHeader()
                operation.responses?.putIfAbsent("409", ApiResponse().description("Idempotency conflict"))
            }
            operation
        }

    private fun Operation.addIdempotencyHeader() {
        val currentParameters = parameters.orEmpty()
        if (currentParameters.any { it.name == IdempotencyHeaders.IDEMPOTENCY_KEY && it.`in` == "header" }) {
            return
        }
        addParametersItem(
            HeaderParameter()
                .name(IdempotencyHeaders.IDEMPOTENCY_KEY)
                .description("Required for idempotent command endpoints. Reuse the same key to replay the first response.")
                .required(true)
                .schema(StringSchema().minLength(1).maxLength(255)),
        )
    }

    private fun HandlerMethod.hasMethodAnnotation(annotationClass: Class<out Annotation>): Boolean =
        getMethodAnnotation(annotationClass) != null
}
