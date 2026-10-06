package dev.sumin.skeleton.legal

import java.time.Instant

/**
 * 장부 한 줄 — (종류, 판, 언어)가 처음 본 순간의 상태. **더하기만 한다**: 줄을 고치거나 지우는 메서드는 없다(저장소는 DB 트리거로도 막는다).
 * 동의 기록이 가리키는 판이 나중에 몰래 바뀌지 않았다는 증거다.
 */
data class LedgerEntry(
    val type: String,
    val version: String,
    val locale: String,
    val status: DocumentStatus,
    val sha256: String,
    val effectiveFrom: Instant?,
    val recordedAt: Instant,
)

/** 장부 포트 — `legal-jdbc` 가 구현한다 */
interface LegalLedger {
    fun find(type: String, version: String, locale: String): LedgerEntry?
    fun all(): List<LedgerEntry>

    /** 이미 같은 (종류, 판, 언어)가 있으면 false — 동시에 뜬 다른 인스턴스가 먼저 넣은 것이다 */
    fun append(entry: LedgerEntry): Boolean
}

/**
 * 시작할 때 장부와 맞춰 본다.
 * - 처음 보는 판은 현재 상태로 장부에 더한다.
 * - **REVIEWED 로 내보낸 판**은 본문 해시 · 시행일 · 상태가 고정이다 — 하나라도 다르면 문제. 고치려면 새 판.
 * - **DRAFT 로 적힌 판**은 편집해도 되지만 같은 판을 REVIEWED 로 올릴 수 없다(초안에 받은 동의가 검토본 동의처럼 보이지 않게) — 새 판.
 * - 내보낸(REVIEWED) 판이 매니페스트에서 사라지면 문제 — 동의 기록이 그 판을 가리킨다. 사라진 초안은 괜찮다.
 * 충돌이 하나라도 있으면 아무것도 더하지 않는다.
 */
class LedgerPin(private val ledger: LegalLedger, private val now: () -> Instant) {
    fun sync(catalog: LegalCatalog): List<String> {
        val problems = mutableListOf<String>()
        val toAppend = mutableListOf<LedgerEntry>()
        val seen = mutableSetOf<Triple<String, String, String>>()
        catalog.all().forEach { v ->
            v.meta.locales.forEach { locale ->
                val source = v.sources[locale] ?: return@forEach
                seen += Triple(v.type, v.version, locale)
                val entry = LedgerEntry(v.type, v.version, locale, v.meta.status, LegalText.sha256(source), v.meta.effectiveFrom, now())
                val existing = ledger.find(v.type, v.version, locale)
                if (existing == null) toAppend += entry else problems += conflicts(existing, entry)
            }
        }
        ledger.all().filter { it.status == DocumentStatus.REVIEWED && Triple(it.type, it.version, it.locale) !in seen }
            .forEach { problems += "${it.type} ${it.version} ${it.locale}: published, but missing from the manifest (consents point at it; published versions are never removed)" }
        if (problems.isNotEmpty()) return problems
        toAppend.forEach { entry ->
            if (!ledger.append(entry)) {
                // 동시에 뜬 다른 인스턴스가 먼저 넣었다 — 그 줄과 맞춰 본다
                val winner = ledger.find(entry.type, entry.version, entry.locale)
                if (winner == null) problems += "${entry.type} ${entry.version} ${entry.locale}: the ledger refused the row" else problems += conflicts(winner, entry)
            }
        }
        return problems
    }

    fun requireClean(catalog: LegalCatalog) {
        val problems = sync(catalog)
        check(problems.isEmpty()) { "skeleton.legal: " + problems.joinToString("; ") }
    }

    private fun conflicts(ledgerRow: LedgerEntry, now: LedgerEntry): List<String> {
        val name = "${now.type} ${now.version} ${now.locale}"
        return when (ledgerRow.status) {
            DocumentStatus.DRAFT ->
                if (now.status == DocumentStatus.REVIEWED) {
                    listOf("$name: was recorded as DRAFT on ${ledgerRow.recordedAt}; a draft is never promoted in place (consents given to the draft would look like consents to the reviewed text) - add a new version for the reviewed text")
                } else {
                    emptyList()
                }
            DocumentStatus.REVIEWED -> if (now.status != DocumentStatus.REVIEWED) {
                listOf("$name: was published as REVIEWED on ${ledgerRow.recordedAt}; its status cannot go back")
            } else buildList {
                if (now.sha256 != ledgerRow.sha256) {
                    add("$name: the text changed after it was published (ledger ${ledgerRow.sha256.take(12)}…, now ${now.sha256.take(12)}…); a published version is never edited - add a new version")
                }
                if (now.effectiveFrom != ledgerRow.effectiveFrom) add("$name: the effective date changed after it was published (was ${ledgerRow.effectiveFrom}, now ${now.effectiveFrom}); add a new version instead")
            }
        }
    }
}
