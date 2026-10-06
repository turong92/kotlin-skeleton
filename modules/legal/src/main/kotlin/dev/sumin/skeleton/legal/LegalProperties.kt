package dev.sumin.skeleton.legal

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.legal")
data class LegalProperties(
    /** 앱이 싣는 문서 디렉터리 — `manifest.json` 과 `<type>/<version>.<locale>.md`. 스프링 리소스 문자열. 거기에 `manifest.json` 이 없으면 모듈의 TEMPLATE 문서로 물러선다 */
    val location: String = "classpath:legal/",
    /** 언어가 없을 때 돌려주는 언어 — 모든 판이 이 언어 원문을 가져야 한다 */
    val defaultLocale: String = "ko",
    /** 서비스를 쓰려면 현재 판에 동의해야 하는 종류. 문서 종류 목록은 매니페스트가 정하고, 여기는 그중 필수인 것만 적는다 (리스트는 기본값을 대체한다) */
    val required: Set<String> = linkedSetOf("terms", "privacy"),
    /** 가입에 필요한 종류 — 안 적으면 [required] 와 같다 */
    val requiredAtSignUp: Set<String>? = null,
    /** 새 판이 시행된 뒤에도 바로 앞 판의 동의를 계속 받는 시간. 0 이면 새 판이 시행되는 순간 바로 앞 판 동의는 낡은 것이 된다 */
    val previousVersionGrace: Duration = Duration.ZERO,
    /** 문서의 `{{키}}` 자리표시를 채우는 사실 (회사 이름 · 연락처 …). 키는 영문자로 시작하고 영숫자 · `_` · `-` */
    val facts: Map<String, String> = emptyMap(),
    /** true 면 모듈의 TEMPLATE 문서를 stage · prod 에서도 내도록 일부러 허락한다 (시험 배포용) */
    val acknowledgeTemplate: Boolean = false,
    /** 운영자 읽기 엔드포인트의 역할 이름 (`ROLE_` 없이) */
    val adminRole: String = "ADMIN",
    val record: Record = Record(),
    val erasure: Erasure = Erasure(),
    val http: Http = Http(),
    val reconsent: Reconsent = Reconsent(),
) {
    init {
        (required + (requiredAtSignUp ?: emptySet())).filter { !LegalCatalog.TYPE.matches(it) }.forEach {
            throw IllegalArgumentException("skeleton.legal.required / required-at-sign-up: '$it' must match ${LegalCatalog.TYPE.pattern}")
        }
        facts.keys.filter { !FACT_KEY.matches(it) }.forEach { throw IllegalArgumentException("skeleton.legal.facts: key '$it' must match ${FACT_KEY.pattern}") }
        require(!previousVersionGrace.isNegative) { "skeleton.legal.previous-version-grace must not be negative" }
        require(LegalCatalog.LOCALE.matches(defaultLocale)) { "skeleton.legal.default-locale '$defaultLocale' must match ${LegalCatalog.LOCALE.pattern}" }
        require(adminRole.isNotBlank()) { "skeleton.legal.admin-role must not be blank" }
        require(location.isNotBlank()) { "skeleton.legal.location must not be blank" }
    }

    val effectiveRequiredAtSignUp: Set<String> get() = requiredAtSignUp ?: required

    data class Record(
        /** 동의 기록에 클라이언트 IP 를 남기나 — 분쟁 때 증거. 개인정보라 [personalDataRetention] 이 지나면 비운다 */
        val storeIp: Boolean = true,
        val storeUserAgent: Boolean = true,
        /** IP · UA 를 이 기간이 지나면 비운다 (동의 기록 줄 자체는 남는다). 0 이면 무기한 */
        val personalDataRetention: Duration = Duration.ofDays(365),
        /** 정리는 이 간격으로 한 번만 (기록할 때 겸사겸사) */
        val retentionInterval: Duration = Duration.ofHours(1),
        /** 한 주체가 하루에 만들 수 있는 동의 사건 수 — 동의 · 철회를 번갈아 눌러 줄을 부풀리는 것을 막는다 */
        val maxEventsPerDay: Int = 200,
    ) {
        init {
            require(maxEventsPerDay > 0) { "skeleton.legal.record.max-events-per-day must be > 0" }
            require(!personalDataRetention.isNegative && !retentionInterval.isNegative) { "skeleton.legal.record durations must not be negative" }
        }
    }

    data class Erasure(
        /** 계정이 지워질 때 — ANONYMIZE: 증거(종류 · 판 · 해시 · 시각)는 남기고 사람(주체 id · IP · UA)을 지운다 · DELETE: 익명화한 뒤 줄도 지운다 */
        val mode: ErasureMode = ErasureMode.ANONYMIZE,
    )

    data class Http(
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/legal",
        /** 공개 문서 읽기의 `Cache-Control: public, max-age` */
        val cacheMaxAge: Duration = Duration.ofMinutes(5),
    ) {
        init {
            require(basePath.startsWith("/")) { "skeleton.legal.http.base-path must start with /" }
            require(!cacheMaxAge.isNegative) { "skeleton.legal.http.cache-max-age must not be negative" }
        }
    }

    data class Reconsent(
        /** true 면 필수 문서에 동의가 모자란(처음이거나 새 판) 로그인 호출자의 보호된 요청에 403 `LEGAL.RECONSENT_REQUIRED` */
        val enabled: Boolean = false,
        val includePaths: List<String> = listOf("/api/**"),
        val excludePaths: List<String> = listOf("/api/v1/legal/**", "/api/v1/auth/**", "/api/v1/account/**"),
    )

    companion object {
        val FACT_KEY = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
    }
}
