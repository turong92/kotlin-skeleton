package dev.sumin.skeleton.common.deploy

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration
@EnableConfigurationProperties(DeployGuardProperties::class)
class DeployGuardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun deployEnvGuard(properties: DeployGuardProperties): DeployEnvGuard = DeployEnvGuard(properties.requireEnv)

    @Bean
    @ConditionalOnMissingBean
    fun deployGuardRunner(guards: ObjectProvider<DeployGuard>, environment: Environment): DeployGuardRunner =
        DeployGuardRunner(guards.orderedStream().toList(), DeployContext.from(environment))
}
