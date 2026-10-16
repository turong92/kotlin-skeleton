package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.consent.SignUpConsentGate
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.erasure.AccountDataExporter
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ResourceLoader

/**
 * 약관 · 동의의 서비스와 고리. 저장소 포트(`ConsentStore` · `LegalLedger`)는 `legal-jdbc` 가 내놓는다 — 없으면 시작이 실패하고 빠진 빈 이름이 메시지에 나온다.
 * 시작할 때 문서 집합을 검사하고(깨졌으면 실패) 장부에 못 박는다(발행된 본문이 바뀌었으면 실패). 앱이 같은 타입의 빈을 만들면 기본 구현이 물러난다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.legal.jdbc.LegalJdbcAutoConfiguration"])
@EnableConfigurationProperties(LegalProperties::class)
class LegalAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun legalRules(properties: LegalProperties, projects: ObjectProvider<ProjectRegistry>): LegalRules {
        val registry = projects.getIfAvailable() ?: return rulesOf(properties)
        return ProjectLegalRules(registry.scopedFrom("skeleton.legal", LegalProperties::class.java) { _, p -> rulesOf(p) })
    }

    /** 앱의 `skeleton.legal.location` 에 `manifest.json` 이 있으면 그것, 없으면 모듈의 TEMPLATE 문서 (stage · prod 에서는 DeployGuard 가 막는다) */
    @Bean
    @ConditionalOnMissingBean
    fun legalCatalog(properties: LegalProperties, rules: LegalRules, loader: ResourceLoader, projects: ObjectProvider<ProjectRegistry>): LegalCatalog {
        val registry = projects.getIfAvailable() ?: return catalogOf(properties, rules, loader)
        // 프로젝트 모드: 프로젝트마다 자기 문서 위치 · 규칙(필수 종류 · 사실 · 기본 언어 · 유예)으로 한 번 만든다
        val parts = registry.scopedFrom("skeleton.legal", LegalProperties::class.java) { _, p -> catalogOf(p, rulesOf(p), loader) }
        registry.active.forEach { project ->
            try {
                parts.of(project)
            } catch (ex: RuntimeException) {
                throw IllegalStateException("Legal documents of project '${project.id}' cannot be loaded: ${ex.message}", ex)
            }
        }
        return ProjectLegalCatalog(parts)
    }

    @Bean
    @ConditionalOnMissingBean
    fun consentRetention(store: ConsentStore, properties: LegalProperties, time: ObjectProvider<TimeProvider>): ConsentRetention =
        ConsentRetention(store, time.getIfAvailable { TimeProvider.systemUtc() }, properties.record.personalDataRetention, properties.record.retentionInterval)

    @Bean
    @ConditionalOnMissingBean
    fun consentService(
        catalog: LegalCatalog,
        rules: LegalRules,
        store: ConsentStore,
        properties: LegalProperties,
        retention: ConsentRetention,
        time: ObjectProvider<TimeProvider>,
    ): ConsentService = ConsentService(
        catalog, rules, store, time.getIfAvailable { TimeProvider.systemUtc() },
        ConsentOptions(properties.record.storeIp, properties.record.storeUserAgent, properties.record.maxEventsPerDay), retention,
    )

    @Bean
    @ConditionalOnMissingBean
    fun legalSignUpGate(service: ConsentService, catalog: LegalCatalog, rules: LegalRules, time: ObjectProvider<TimeProvider>): SignUpConsentGate =
        LegalSignUpGate(service, catalog, rules, time.getIfAvailable { TimeProvider.systemUtc() })

    /** 이름으로 조건을 건다 — 다른 모듈의 [AccountErasureListener] 들과 나란히 등록돼야 한다 */
    @Bean
    @ConditionalOnMissingBean(name = ["legalAccountErasureListener"])
    fun legalAccountErasureListener(store: ConsentStore, properties: LegalProperties): AccountErasureListener =
        ConsentErasureListener(store, properties.erasure.mode)

    @Bean
    @ConditionalOnMissingBean(name = ["legalDataExporter"])
    fun legalDataExporter(store: ConsentStore): AccountDataExporter = ConsentDataExporter(store)

    @Bean
    @ConditionalOnMissingBean(name = ["legalDeployGuard"])
    fun legalDeployGuard(catalog: LegalCatalog, properties: LegalProperties, time: ObjectProvider<TimeProvider>, projects: ObjectProvider<ProjectRegistry>): DeployGuard {
        val now = { time.getIfAvailable { TimeProvider.systemUtc() }.now() }
        val registry = projects.getIfAvailable() ?: return LegalDeployGuard(catalog, properties.acknowledgeTemplate, now)
        // 프로젝트 모드: 프로젝트마다 그 프로젝트의 문서 · `acknowledge-template` 로 같은 규칙을 본다 — 메시지 앞에 `project '<id>': `
        val guards = registry.scopedFrom("skeleton.legal", LegalProperties::class.java) { _, p -> LegalDeployGuard(catalog, p.acknowledgeTemplate, now) }
        return object : DeployGuard {
            override val name = "legal"
            override fun problems(context: DeployContext) = perProject { it.problems(context) }
            override fun warnings(context: DeployContext) = perProject { it.warnings(context) }
            private fun perProject(of: (LegalDeployGuard) -> List<String>) =
                registry.active.flatMap { project -> ProjectContext.runIn(project) { of(guards.of(project)) }.map { "project '${project.id}': $it" } }
        }
    }

    /** 모든 빈이 만들어진 직후(웹 서버가 열리기 전) — 문서 집합이 쓸 만한지, 발행된 본문이 장부와 같은지 */
    @Bean
    @ConditionalOnMissingBean(name = ["legalStartupCheck"])
    fun legalStartupCheck(catalog: LegalCatalog, ledger: LegalLedger, time: ObjectProvider<TimeProvider>, projects: ObjectProvider<ProjectRegistry>): SmartInitializingSingleton = SmartInitializingSingleton {
        val pin = LedgerPin(ledger) { time.getIfAvailable { TimeProvider.systemUtc() }.now() }
        // 프로젝트가 있으면 문서 집합도 장부도 프로젝트마다(장부는 스키마마다) 있다 — 차례로 본다
        val registry = projects.getIfAvailable()
        if (registry == null) {
            catalog.requireValid()
            pin.requireClean(catalog)
        } else {
            registry.forEachProject { project ->
                try {
                    catalog.requireValid()
                    pin.requireClean(catalog)
                } catch (ex: IllegalStateException) {
                    throw IllegalStateException("project '${project.id}': ${ex.message}", ex)
                }
            }
        }
    }

    private fun rulesOf(properties: LegalProperties) = LegalRules(
        properties.required, properties.effectiveRequiredAtSignUp, properties.defaultLocale, properties.previousVersionGrace, properties.facts,
    )

    /** 앱의 `skeleton.legal.location` 에 `manifest.json` 이 있으면 그것, 없으면 모듈의 TEMPLATE 문서 (stage · prod 에서는 DeployGuard 가 막는다) */
    private fun catalogOf(properties: LegalProperties, rules: LegalRules, loader: ResourceLoader): LegalCatalog {
        val app = LegalSources(properties.location, loader)
        val manifest = app.manifest()
        if (manifest != null) return LegalCatalog(manifest, app::read, rules)
        val templates = LegalSources(TEMPLATE_LOCATION, loader)
        return LegalCatalog(templates.manifest() ?: error("the module's template documents are missing from the class path"), templates::read, rules)
    }

    companion object {
        const val TEMPLATE_LOCATION = "classpath:legal-templates/"
    }
}
