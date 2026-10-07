package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountAuthorDirectory
import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.DisplayNameRules
import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.common.author.AuthorAdoptionListener
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 작성자 프로필 — `account` 위에 얹힌 선택 모듈이 platform 의 고리 넷(`AuthorIdentities` · `AuthorDirectory` · `AccountErasureListener` · 넘겨받기)을 채운다.
 * 저장소 포트([AuthorProfileRepository])는 `author-profile-jdbc` 가 내놓는다 — 없으면 시작이 실패하고 빠진 빈 이름이 메시지에 나온다 (`board` 와 같다).
 *
 * **[AccountAutoConfiguration] 보다 먼저** 평가한다: 거기서 `AuthorDirectory` 는 `@ConditionalOnMissingBean(AuthorDirectory::class)` 라서
 * 이 설정이 먼저 빈을 둬야 프로필 디렉터리가 이긴다 (`prf_` 가 아닌 id 는 이 디렉터리가 `account` 의 기본 구현에 그대로 넘긴다). 모든 빈은 [ConditionalOnMissingBean] — 앱이 같은 타입을 두면 물러난다.
 * HTTP(`/author-profiles`)와 관리자 조회는 이 조각에 없다.
 */
@AutoConfiguration(before = [AccountAutoConfiguration::class], afterName = ["dev.sumin.skeleton.authorprofile.jdbc.AuthorProfileJdbcAutoConfiguration"])
@EnableConfigurationProperties(AuthorProfileProperties::class)
class AuthorProfileAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun authorProfileService(
        repository: AuthorProfileRepository,
        accounts: ObjectProvider<AccountRepository>,
        accountProperties: AccountProperties,
        rules: ObjectProvider<DisplayNameRules>,
        limits: AccountRateLimits,
        properties: AuthorProfileProperties,
        time: ObjectProvider<TimeProvider>,
    ): AuthorProfileService = AuthorProfileService(
        repository, { accounts.getObject() },
        DisplayNames(accountProperties.displayName, rules = rules.getIfAvailable { dev.sumin.skeleton.account.DefaultDisplayNameRules }),
        limits, properties, time.getIfAvailable { TimeProvider.systemUtc() },
    )

    /** 쓰기 직전에 "이 계정이 이 프로필로 써도 되는가" — 이 빈이 있으면 board 가 쓰기 규칙 · 공개 작성자 id 를 켠다 (스펙 §7.1) */
    @Bean
    @ConditionalOnMissingBean(AuthorIdentities::class)
    fun authorProfileIdentities(repository: AuthorProfileRepository): AuthorIdentities = AuthorProfileIdentities(repository)

    /** `prf_` 는 이 디렉터리가, 그 밖의 id(아직 넘겨받지 않은 옛 행의 계정 id)는 `account` 의 기본 디렉터리가 푼다 */
    @Bean
    @ConditionalOnMissingBean(AuthorDirectory::class)
    fun authorProfileDirectory(repository: AuthorProfileRepository, accounts: ObjectProvider<AccountRepository>): AuthorDirectory =
        AuthorProfileDirectory(repository, AuthorDirectory { ids, context -> AccountAuthorDirectory(accounts.getObject()).resolve(ids, context) })

    @Bean
    @ConditionalOnMissingBean(name = ["authorProfileErasureListener"])   // 고리는 모듈마다 하나씩 있어 타입이 아니라 이름으로 비킨다 (BoardAccountErasureListener 와 같다)
    fun authorProfileErasureListener(repository: AuthorProfileRepository): AccountErasureListener = AuthorProfileErasureListener(repository)

    /** 기동할 때 옛 글을 첫 프로필로 넘겨받는다 (`skeleton.author-profile.adopt-on-startup`, 기본 켜짐) — 끄면 아무것도 하지 않는다 */
    @Bean
    @ConditionalOnMissingBean(name = ["authorProfileAdoptionRunner"])
    fun authorProfileAdoptionRunner(
        listeners: ObjectProvider<AuthorAdoptionListener>,
        service: AuthorProfileService,
        properties: AuthorProfileProperties,
    ): ApplicationRunner = ApplicationRunner {
        if (properties.adoptOnStartup) AuthorAdoption({ listeners.orderedStream().toList() }, service).run()
    }
}
