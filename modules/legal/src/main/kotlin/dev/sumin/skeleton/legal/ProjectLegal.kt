package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.project.ProjectScoped
import java.time.Duration
import java.time.Instant

/** 프로젝트 모드의 규칙 — 읽을 때마다 지금 프로젝트의 값 (필수 종류 · 사실 · 기본 언어 · 유예). 프로젝트 없이 읽으면 `NoProjectContextException` */
class ProjectLegalRules(private val parts: ProjectScoped<LegalRules>) : LegalRules(emptySet(), emptySet(), "", Duration.ZERO, emptyMap()) {
    override val required get() = parts.get().required
    override val requiredAtSignUp get() = parts.get().requiredAtSignUp
    override val defaultLocale get() = parts.get().defaultLocale
    override val grace get() = parts.get().grace
    override val facts get() = parts.get().facts
}

/** 프로젝트 모드의 문서 집합 — 부를 때마다 지금 프로젝트의 문서 위치 · 규칙으로 만든 [LegalCatalog] 에 넘긴다 */
class ProjectLegalCatalog(private val parts: ProjectScoped<LegalCatalog>) : LegalCatalog(emptyList(), { _, _, _ -> null }, LegalRules(emptySet(), emptySet(), "", Duration.ZERO, emptyMap())) {
    private val target get() = parts.get()

    override val types: Set<String> get() = target.types
    override fun all() = target.all()
    override fun versionsOf(type: String) = target.versionsOf(type)
    override fun current(type: String, now: Instant) = target.current(type, now)
    override fun previous(type: String, now: Instant) = target.previous(type, now)
    override fun next(type: String, now: Instant) = target.next(type, now)
    override fun agreeable(type: String, version: String, now: Instant) = target.agreeable(type, version, now)
    override fun graceUntil(type: String, now: Instant) = target.graceUntil(type, now)
    override fun find(type: String, version: String, now: Instant) = target.find(type, version, now)
    override fun render(document: DocumentVersion, locale: String) = target.render(document, locale)
    override fun missingFacts(now: Instant, includeTemplates: Boolean) = target.missingFacts(now, includeTemplates)
    override val problems: List<String> get() = target.problems
    override fun requireValid() = target.requireValid()
}
