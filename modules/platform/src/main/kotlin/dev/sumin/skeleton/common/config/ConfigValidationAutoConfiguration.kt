package dev.sumin.skeleton.common.config

import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration
@EnableConfigurationProperties(ConfigValidationProperties::class)
class ConfigValidationAutoConfiguration {
    @Bean
    fun requiredConfigValidationRunner(
        environment: Environment,
        properties: ConfigValidationProperties,
    ): ApplicationRunner =
        ApplicationRunner {
            RequiredConfigValidator.validate(
                activeProfiles = environment.activeProfiles.toSet(),
                properties = properties,
                propertyResolver = PropertyResolver { name -> environment.getProperty(name) },
            )
        }
}
