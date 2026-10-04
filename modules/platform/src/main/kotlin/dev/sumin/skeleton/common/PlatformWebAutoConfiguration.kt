package dev.sumin.skeleton.common

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * Registers the platform's request-scoped web beans. A consuming app never component-scans module
 * packages. The filters carry no stereotype annotation; the exception advice keeps `@RestControllerAdvice`
 * because Spring MVC finds advice only through that annotation, and is registered here as a `@Bean`.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PlatformWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun traceIdFilter(): TraceIdFilter = TraceIdFilter()

    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingFilter(): RequestLoggingFilter = RequestLoggingFilter()

    @Bean
    @ConditionalOnMissingBean
    fun globalExceptionHandler(): GlobalExceptionHandler = GlobalExceptionHandler()
}
