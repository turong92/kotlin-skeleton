package dev.sumin.skeleton.persistence.jpa

import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(afterName = ["org.springframework.boot.jpa.autoconfigure.JpaBaseConfiguration"])
@ConditionalOnClass(EntityManager::class)
class JpaPersistenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jpaPartialUpdateExecutor(entityManager: EntityManager): JpaPartialUpdateExecutor =
        JpaPartialUpdateExecutor(entityManager)

    @Bean
    @ConditionalOnMissingBean
    fun jpaFetchGraphHints(entityManager: EntityManager): JpaFetchGraphHints =
        JpaFetchGraphHints(entityManager)
}
