package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.search.erasure.SearchAccountErasureListener
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 검색 질의([SearchReader]) · 운영([SearchAdmin]) · 운영 권한 · 탈퇴 고리. 엔진([SearchEngine])은 `search-jdbc` 가 내놓는다 - 없으면 서비스 빈이 켜지지 않는다.
 * `catalog` · `board` 같은 다른 모듈을 모른다 - 그쪽이 platform 의 `SearchIndexer` 로 밀어 넣고 `SearchSource` 빈을 둘 뿐이다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.search.jdbc.SearchJdbcAutoConfiguration"])
@EnableConfigurationProperties(SearchProperties::class)
class SearchAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["searchPermissionDeclaration"])
    fun searchPermissionDeclaration(): PermissionDeclaration = SearchPermissions.declaration()

    @Bean
    @ConditionalOnBean(SearchEngine::class)
    @ConditionalOnMissingBean
    fun searchReader(engine: SearchEngine): SearchReader = SearchReader(engine)

    @Bean
    @ConditionalOnBean(SearchEngine::class, SearchRebuilds::class)
    @ConditionalOnMissingBean
    fun searchAdmin(engine: SearchEngine, rebuilds: SearchRebuilds, sources: ObjectProvider<SearchSource>): SearchAdmin =
        SearchAdmin(engine, rebuilds) { sources.orderedStream().toList() }

    @Bean
    @ConditionalOnBean(SearchEngine::class)
    @ConditionalOnMissingBean(name = ["searchAccountErasureListener"])
    fun searchAccountErasureListener(engine: SearchEngine): AccountErasureListener = SearchAccountErasureListener(engine)
}
