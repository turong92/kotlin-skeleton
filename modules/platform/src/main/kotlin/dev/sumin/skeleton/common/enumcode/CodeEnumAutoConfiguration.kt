package dev.sumin.skeleton.common.enumcode

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.format.FormatterRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.JacksonModule

@AutoConfiguration(before = [JacksonAutoConfiguration::class])
class CodeEnumAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun codeEnumSpringConverter(): CodeEnumSpringConverter =
        CodeEnumSpringConverter()

    @Bean
    @ConditionalOnMissingBean(name = ["codeEnumWebMvcConfigurer"])
    fun codeEnumWebMvcConfigurer(converter: CodeEnumSpringConverter): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addFormatters(registry: FormatterRegistry) {
                registry.addConverter(converter)
            }
        }

    @Bean
    @ConditionalOnMissingBean
    fun codeEnumJacksonModule(): JacksonModule =
        CodeEnumJacksonModule()

    @Bean
    @ConditionalOnClass(OpenApiCustomizer::class)
    @ConditionalOnMissingBean(name = ["codeEnumOpenApiCustomizer"])
    fun codeEnumOpenApiCustomizer(applicationContext: ApplicationContext): OpenApiCustomizer =
        CodeEnumOpenApiCustomizer(applicationContext)
}
