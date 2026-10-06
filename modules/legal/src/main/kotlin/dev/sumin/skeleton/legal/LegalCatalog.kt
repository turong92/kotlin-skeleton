package dev.sumin.skeleton.legal

import java.time.Duration
import java.time.Instant

enum class DocumentStatus { DRAFT, REVIEWED }

/**
 * 매니페스트(`manifest.json`)의 판 하나. [effectiveFrom] 이 없으면 시행 예정이 없는 판(작업 중인 초안)이라 현재 판도 다음 판도 되지 않는다.
 * [sha256] 은 언어별 원문 해시 — REVIEWED 판은 필수(검토된 본문을 못 박는다), DRAFT 는 비워도 된다. [template] 은 모듈이 예시로 주는 문서라는 표시.
 */
data class ManifestVersion(
    val type: String,
    val version: String,
    val status: DocumentStatus,
    val effectiveFrom: Instant?,
    val template: Boolean,
    val locales: List<String>,
    val sha256: Map<String, String>,
)

/** 문서 집합을 해석하는 규칙 — 설정에서 온다. [grace]: 새 판이 시행된 뒤 바로 앞 판의 동의를 계속 받아 주는 시간(0 이면 받지 않는다) */
class LegalRules(
    val required: Set<String>,
    val requiredAtSignUp: Set<String>,
    val defaultLocale: String,
    val grace: Duration,
    val facts: Map<String, String>,
)

class DocumentVersion(val meta: ManifestVersion, val sources: Map<String, String>) {
    val type get() = meta.type
    val version get() = meta.version
    val effectiveFrom get() = meta.effectiveFrom
    val locales get() = meta.locales.filter { it in sources }
}

data class RenderedDocument(
    val type: String,
    val version: String,
    val locale: String,
    val requestedLocale: String,
    val title: String?,
    val markdown: String,
    /** 원문(자리표시가 채워지기 전) 정규화본의 SHA-256 — 동의 기록이 가리키는 값 */
    val sha256: String,
    val missingFacts: Set<String>,
)

/**
 * 앱이 가져온 문서 집합 — 매니페스트 + 원문. 현재 판 · 다음 판 · 유예 · 읽기 · 시작 때 검사를 한곳에서 답한다. 시계는 받지 않는다: 모든 질문이 `now` 를 인자로 받는다.
 * 원문은 처음 쓸 때 한 번 읽는다 ([reader] 가 못 찾으면 null).
 */
class LegalCatalog(
    private val manifest: List<ManifestVersion>,
    private val reader: (type: String, version: String, locale: String) -> String?,
    private val rules: LegalRules,
) {
    private val versions: List<DocumentVersion> by lazy {
        manifest.map { m -> DocumentVersion(m, m.locales.mapNotNull { l -> reader(m.type, m.version, l)?.let { l to it } }.toMap()) }
    }

    val types: Set<String> get() = manifest.map { it.type }.toCollection(linkedSetOf())

    fun all(): List<DocumentVersion> = versions

    fun versionsOf(type: String): List<DocumentVersion> = versions.filter { it.type == type }

    private fun inForce(type: String, now: Instant) =
        versionsOf(type).filter { it.effectiveFrom != null && !it.effectiveFrom!!.isAfter(now) }

    fun current(type: String, now: Instant): DocumentVersion? = inForce(type, now).maxByOrNull { it.effectiveFrom!! }

    /** 바로 앞 판 — 지금 현재 판 직전에 시행되던 것 */
    fun previous(type: String, now: Instant): DocumentVersion? {
        val current = current(type, now) ?: return null
        return inForce(type, now).filter { it.effectiveFrom!! < current.effectiveFrom!! }.maxByOrNull { it.effectiveFrom!! }
    }

    /** 시행 예정인 가장 이른 판 */
    fun next(type: String, now: Instant): DocumentVersion? =
        versionsOf(type).filter { it.effectiveFrom != null && it.effectiveFrom!!.isAfter(now) }.minByOrNull { it.effectiveFrom!! }

    /** 지금 동의로 받는 판 — 현재 판, 또는 유예가 켜져 있고 새 판 시행 뒤 [LegalRules.grace] 안이면 바로 앞 판 */
    fun agreeable(type: String, version: String, now: Instant): DocumentVersion? {
        val current = current(type, now) ?: return null
        if (current.version == version) return current
        val previous = previous(type, now) ?: return null
        val end = graceUntil(type, now) ?: return null
        return previous.takeIf { it.version == version && now.isBefore(end) }
    }

    /** 앞 판 동의를 받아 주는 끝 시각 — 유예가 0 이거나 앞 판이 없으면 null */
    fun graceUntil(type: String, now: Instant): Instant? {
        if (rules.grace.isZero || rules.grace.isNegative) return null
        val current = current(type, now) ?: return null
        previous(type, now) ?: return null
        return current.effectiveFrom!!.plus(rules.grace)
    }

    /** 읽을 수 있는 판 — 시행된(`effectiveFrom <= now`) 판만. 옛 판도 읽힌다 */
    fun find(type: String, version: String, now: Instant): DocumentVersion? =
        inForce(type, now).firstOrNull { it.version == version }

    fun render(document: DocumentVersion, locale: String): RenderedDocument {
        val shown = if (locale in document.sources) locale else rules.defaultLocale
        val source = document.sources.getValue(shown)
        val rendered = LegalText.render(source, rules.facts)
        return RenderedDocument(
            document.type, document.version, shown, locale, LegalText.title(rendered.text), rendered.text, LegalText.sha256(source), rendered.missing,
        )
    }

    /** 지금 내줄 수 있는 판(현재 · 다음)의 원문이 쓰는 자리표시 중 설정에 없는 사실 */
    fun missingFacts(now: Instant): Set<String> =
        types.flatMap { listOfNotNull(current(it, now), next(it, now)) }
            .flatMap { v -> v.sources.values.flatMap { LegalText.placeholders(it) } }
            .filter { it !in rules.facts }.toCollection(linkedSetOf())

    /** 시작할 때 막아야 하는 것들 — 사람이 읽을 수 있는 문장. 비면 문서 집합이 쓸 만하다 */
    val problems: List<String> by lazy { computeProblems() }

    fun requireValid() {
        check(problems.isEmpty()) { "skeleton.legal: " + problems.joinToString("; ") }
    }

    private fun computeProblems(): List<String> {
        val out = mutableListOf<String>()
        versions.forEach { v ->
            val m = v.meta
            val name = "${m.type} ${m.version}"
            if (!TYPE.matches(m.type)) out += "document type '${m.type}' must match ${TYPE.pattern}"
            if (!VERSION.matches(m.version)) out += "version '${m.version}' of ${m.type} must match ${VERSION.pattern}"
            if (m.locales.isEmpty()) out += "$name has no locales"
            m.locales.filter { !LOCALE.matches(it) }.forEach { out += "$name locale '$it' must match ${LOCALE.pattern}" }
            if (m.status == DocumentStatus.REVIEWED && m.effectiveFrom == null) out += "$name is REVIEWED but has no effectiveFrom"
            if (rules.defaultLocale !in m.locales) out += "$name has no source in the default locale ${rules.defaultLocale}"
            m.locales.forEach { locale ->
                val source = v.sources[locale]
                if (source == null) {
                    out += "$name $locale: source file is missing"
                    return@forEach
                }
                LegalText.problems(source).forEach { out += "$name $locale: $it" }
                val computed = LegalText.sha256(source)
                val declared = m.sha256[locale]
                when {
                    declared == null && m.status == DocumentStatus.REVIEWED -> out += "$name $locale: REVIEWED needs sha256 in the manifest (computed $computed)"
                    declared != null && declared != computed -> out += "$name $locale: sha256 in the manifest differs from the source (manifest $declared, computed $computed)"
                }
            }
        }
        versions.groupBy { it.type }.forEach { (type, list) ->
            list.groupBy { it.version }.filter { it.value.size > 1 }.keys.forEach { out += "$type $it is listed twice" }
            list.filter { it.effectiveFrom != null }.groupBy { it.effectiveFrom }.values.filter { it.size > 1 }
                .forEach { same -> out += "${same[0].type} ${same[0].version} and ${same[1].type} ${same[1].version} take effect at the same instant" }
        }
        (rules.required + rules.requiredAtSignUp).filter { it !in types }.distinct().forEach { out += "required document type '$it' has no version in the manifest" }
        return out
    }

    companion object {
        val TYPE = Regex("^[a-z][a-z0-9-]{1,31}$")
        val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")
        val LOCALE = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$")
    }
}
