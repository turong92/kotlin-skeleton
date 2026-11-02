package dev.sumin.skeleton.storage.image.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.ImageVariantProcessor
import dev.sumin.skeleton.storage.image.ImageVariantTasks
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * `storage-image` 의 상태 저장소([ImageStatusStore])를 JDBC(PostgreSQL · MySQL)로 구현하고 변환 작업을 `job-queue-jdbc` 에 얹는다 - `skeleton.storage.image.enabled=true` 일 때만.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261102100000__storage_images.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 * 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다 - 변환을 약속하고 뒷일을 할 곳이 없다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "skeleton.storage.image", name = ["enabled"], havingValue = "true")
class StorageImageJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ImageStatusStore::class)
    fun jdbcImageStatusStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect, time: ObjectProvider<TimeProvider>): ImageStatusStore =
        JdbcImageStatusStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean(ImageVariantTasks::class)
    fun jdbcImageVariantTasks(queue: JobQueue): ImageVariantTasks = JdbcImageVariantTasks(queue)

    /** 큐 워커가 `JobHandler` 빈을 워커가 만들어질 때 모은다 - 처리기는 늦게 찾는다(순환을 피한다) */
    @Bean
    @ConditionalOnMissingBean(ImageVariantJobHandler::class)
    fun imageVariantJobHandler(processor: ObjectProvider<ImageVariantProcessor>): ImageVariantJobHandler = ImageVariantJobHandler { processor.getObject() }

    @Bean
    @ConditionalOnMissingBean(ImageVariantDeadListener::class)
    fun imageVariantDeadListener(store: ObjectProvider<ImageStatusStore>): ImageVariantDeadListener = ImageVariantDeadListener { store.getObject() }
}
