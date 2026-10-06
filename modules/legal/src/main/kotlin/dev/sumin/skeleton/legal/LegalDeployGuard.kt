package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import java.time.Instant

/**
 * 법적 문서로 서도 되는 상태인가 — stage · prod(`skeleton.env`)에서만 막고, 그 밖에는 경고만 한다.
 * - 모듈이 예시로 주는 **TEMPLATE 문서**를 내는 중이면 막는다. 앱이 자기 문서로 바꾸거나(`skeleton.legal.location`), 일부러 쓰겠다고 밝혀야 한다(`skeleton.legal.acknowledge-template=true` — 시험 배포용).
 * - **DRAFT** 판이 시행일을 가졌으면(현재 판이 될 수 있으면) 막는다 — 현재 판이 되려면 REVIEWED 여야 한다. 시행일이 없는 초안은 작업 중이라 괜찮다.
 * - 문서의 `{{사실}}` 자리표시 중 설정에 없는 것이 있으면 막는다 (`skeleton.legal.facts.<키>`). 값은 메시지에 싣지 않는다. TEMPLATE 문서의 자리표시는 예시라 보지 않는다.
 */
class LegalDeployGuard(private val catalog: LegalCatalog, private val acknowledgeTemplate: Boolean, private val now: () -> Instant) : DeployGuard {
    override val name: String = "legal"

    private fun served() = catalog.all().filter { it.effectiveFrom != null }

    override fun problems(context: DeployContext): List<String> {
        if (!context.protectedEnv) return emptyList()
        val out = mutableListOf<String>()
        val templates = served().filter { it.meta.template }
        if (templates.isNotEmpty() && !acknowledgeTemplate) {
            out += "legal documents are the module's TEMPLATE text (${names(templates)}): replace them with the app's own reviewed documents (skeleton.legal.location) " +
                "or acknowledge the template for a trial deploy (skeleton.legal.acknowledge-template=true)"
        }
        val drafts = served().filter { !it.meta.template && it.meta.status == DocumentStatus.DRAFT }
        if (drafts.isNotEmpty()) out += "legal documents ${names(drafts)} are DRAFT but have an effectiveFrom; only REVIEWED documents may be current in stage/prod"
        catalog.missingFacts(now(), includeTemplates = false).takeIf { it.isNotEmpty() }?.let { missing ->
            out += "legal documents use placeholders without a fact: " + missing.joinToString { "skeleton.legal.facts.$it" }
        }
        return out
    }

    override fun warnings(context: DeployContext): List<String> {
        val out = mutableListOf<String>()
        val templates = served().filter { it.meta.template }
        if (templates.isNotEmpty()) {
            out += "legal documents are the module's TEMPLATE text (${names(templates)})" +
                if (acknowledgeTemplate) " - skeleton.legal.acknowledge-template is on; replace them before real users sign up" else " - stage/prod refuse to start with them"
        }
        if (!context.protectedEnv) {
            val drafts = served().filter { !it.meta.template && it.meta.status == DocumentStatus.DRAFT }
            if (drafts.isNotEmpty()) out += "legal documents ${names(drafts)} are DRAFT and have an effectiveFrom; stage/prod refuse that"
            catalog.missingFacts(now(), includeTemplates = false).takeIf { it.isNotEmpty() }?.let { missing ->
                out += "legal documents use placeholders without a fact: " + missing.joinToString { "skeleton.legal.facts.$it" }
            }
        }
        return out
    }

    private fun names(versions: List<DocumentVersion>) = versions.joinToString { "${it.type} ${it.version}" }
}
