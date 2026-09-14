package dev.sumin.skeleton.common

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * Registers the platform's request-scoped web beans so that a consuming app does not have to
 * component-scan `dev.sumin.skeleton`. The classes keep their stereotype annotations for apps that
 * do scan; in that case the scanned bean wins and these definitions back off.
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
