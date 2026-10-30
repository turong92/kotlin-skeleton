package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.search.SearchEngine
import dev.sumin.skeleton.search.SearchProperties
import dev.sumin.skeleton.search.SearchRebuilds
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * 검색 엔진([SearchEngine])과 재색인([SearchRebuilds])을 JDBC(PostgreSQL · MySQL)로 구현한다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261030050000__search.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.search.jdbc.SearchJdbcAutoConfiguration`)을 `search` 의 `SearchAutoConfiguration` 이 문자열로 가리킨다** - 그쪽은 엔진 빈이 있어야 켜진다.
 * 큐(`job-queue-jdbc`)가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다 - 재색인을 보낼 곳이 없다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class SearchJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SearchEngine::class)
    fun jdbcSearchEngine(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): SearchEngine =
        JdbcSearchEngine(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect)

    @Bean
    @ConditionalOnMissingBean
    fun jdbcSearchRebuilds(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        dialect: SqlDialect,
        time: ObjectProvider<TimeProvider>,
        queue: JobQueue,
        properties: SearchProperties,
    ): JdbcSearchRebuilds =
        JdbcSearchRebuilds(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect, time.getIfAvailable { TimeProvider.systemUtc() }, queue, properties.rebuild.staleAfter)

    @Bean
    @ConditionalOnMissingBean(SearchRebuilds::class)
    fun searchRebuilds(rebuilds: JdbcSearchRebuilds): SearchRebuilds = rebuilds

    @Bean
    @ConditionalOnMissingBean(SearchRebuildJobHandler::class)
    fun searchRebuildJobHandler(
        engine: SearchEngine,
        rebuilds: JdbcSearchRebuilds,
        queue: JobQueue,
        transactionManager: PlatformTransactionManager,
        sources: ObjectProvider<SearchSource>,
    ): SearchRebuildJobHandler = SearchRebuildJobHandler(engine, rebuilds, queue, transactionManager, { sources.orderedStream().toList() })

    /** MySQL 의 ngram 조각 크기가 2 가 아니면 1글자 · 3글자 이상 단위로 색인되어 질의(2글자 이상 덩어리)와 어긋난다 - 경고만 한다 */
    @Bean
    fun searchNgramCheck(dataSource: DataSource, dialect: SqlDialect): SmartInitializingSingleton = SmartInitializingSingleton {
        if (dialect.vendor != "mysql") return@SmartInitializingSingleton
        val size = runCatching { NamedParameterJdbcTemplate(dataSource).queryForObject("select @@ngram_token_size", emptyMap<String, Any>(), Int::class.java) }.getOrNull()
        ngramWarning(size)?.let { LoggerFactory.getLogger(SearchJdbcAutoConfiguration::class.java).warn(it) }
    }
}

/** 질의가 2글자 조각을 전제로 하므로 `ngram_token_size` 가 2 가 아니면 경고 문구, 맞거나 읽지 못했으면 null */
internal fun ngramWarning(ngramTokenSize: Int?): String? =
    if (ngramTokenSize == null || ngramTokenSize == 2) null
    else "MySQL ngram_token_size is $ngramTokenSize but skeleton search queries assume 2: set ngram_token_size=2 (server startup option) and rebuild the FULLTEXT indexes"
