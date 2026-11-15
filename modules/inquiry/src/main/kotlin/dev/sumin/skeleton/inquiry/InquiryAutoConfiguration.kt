package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.time.asClock
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.PeriodicRunCheck
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.common.web.RateLimitStore
import java.time.Duration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.env.Environment
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * ```yaml
 * skeleton:
 *   inquiry:
 *     enabled: true       # false 면 공개 경로 POST /api/v1/inquiries 를 등록하지 않는다
 *     per-client: 5       # 로그인하지 않은 호출: 클라이언트(IP)마다 window 동안 문의 수
 *     per-account: 10     # 로그인한 호출: 계정마다 window 동안 문의 수
 *     window: 1h
 *     retention:          # 비워 두면 지우지 않는다. 예: 365d - 처리 완료 뒤 이만큼 지난 문의를 매일 지운다(0 이하면 기동 실패)
 *     admin:
 *       enabled: false    # true 면 운영자 경로(목록 · 처리 표시) - 권한 inquiry.read · inquiry.handle
 * ```
 */
@ConfigurationProperties("skeleton.inquiry")
data class InquiryProperties(
    val enabled: Boolean = true,
    val perClient: Int = 5,
    val perAccount: Int = 10,
    val window: Duration = Duration.ofHours(1),
    val admin: Admin = Admin(),
    /** 처리 완료(DONE) 문의를 처리 시각부터 이만큼 보관한 뒤 매일 지운다. **기본 null = 지우지 않는다**(앱이 켠다). 켰는데 0 이하면 기동 실패 */
    val retention: Duration? = null,
) {
    init {
        require(perClient >= 1 && perAccount >= 1) { "skeleton.inquiry.per-client / per-account must be at least 1" }
        require(!window.isNegative && !window.isZero) { "skeleton.inquiry.window must be positive" }
        require(retention == null || (!retention.isNegative && !retention.isZero)) { "skeleton.inquiry.retention must be positive when set (leave it unset to keep handled inquiries)" }
    }

    data class Admin(
        /** 운영자 경로 `GET /api/v1/admin/inquiries` · `PUT …/{id}/status` - 모듈 기본은 꺼짐(404) */
        val enabled: Boolean = false,
    )
}

/** 계정이 지워질 때 문의의 계정 연결만 끊는다 — 본문 · 답장 이메일은 남는다. 멱등 */
class InquiryErasureListener(private val repository: InquiryRepository) : AccountErasureListener {
    override val name: String = "inquiry"

    override fun erase(request: ErasureRequest) {
        repository.detachAccount(request.accountId)
    }
}

/**
 * 접수 서비스 · 운영 서비스 · 탈퇴 고리. 저장소는 `inquiry-jdbc` 가 낸다(없으면 메모리 — 시험 · DB 없는 조립용).
 * 사람 확인 기본은 통과다 — `captcha-turnstile` 이 있으면 [dev.sumin.skeleton.inquiry.captcha.InquiryTurnstileAutoConfiguration] 이 대신 서고, 아니면 앱이 [InquiryHumanCheck] 빈을 둔다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.inquiry.jdbc.InquiryJdbcAutoConfiguration"])
@EnableConfigurationProperties(InquiryProperties::class)
class InquiryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(InquiryRepository::class)
    fun inMemoryInquiryRepository(): InquiryRepository = InMemoryInquiryRepository()

    @Bean
    @ConditionalOnMissingBean(InquiryHumanCheck::class)
    fun passingInquiryHumanCheck(): InquiryHumanCheck = InquiryHumanCheck { _, _ -> true }

    @Bean
    @ConditionalOnMissingBean
    fun inquiryService(
        repository: InquiryRepository,
        humanCheck: InquiryHumanCheck,
        listeners: ObjectProvider<InquiryListener>,
        properties: InquiryProperties,
        time: TimeProvider,
        limits: ObjectProvider<RateLimitStore>,
    ): InquiryService = InquiryService(
        repository, humanCheck, listeners.orderedStream().toList(), properties, time,
        limits.getIfAvailable { InMemoryFixedWindowRateLimitStore(time.asClock()) },
    )

    /** 보관 기한을 정했을 때만 - 기본 꺼짐(값을 비운 `retention:` 도 꺼짐). 돌릴 길(scheduler · 요청 구동)이 없으면 조용히 안 지우는 대신 기동 실패 */
    @Bean
    @ConditionalOnExpression("'\${skeleton.inquiry.retention:}'.trim() != ''")
    @ConditionalOnMissingBean
    fun inquiryRetention(repository: InquiryRepository, properties: InquiryProperties, time: TimeProvider, environment: Environment): InquiryRetention {
        PeriodicRunCheck.require("skeleton.inquiry.retention", environment)
        return InquiryRetention(repository, checkNotNull(properties.retention), time)
    }

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - 지우기가 멱등이다 */
    @Bean
    @ConditionalOnBean(InquiryRetention::class)
    @ConditionalOnMissingBean(name = ["inquiryRetentionTask"])
    fun inquiryRetentionTask(retention: InquiryRetention): OnRequestTask = SimpleOnRequestTask("inquiry-retention") { retention.purge() }

    @Bean
    @ConditionalOnMissingBean
    fun inquiryAdminService(repository: InquiryRepository, time: TimeProvider): InquiryAdminService = InquiryAdminService(repository, time)

    @Bean
    @ConditionalOnMissingBean(name = ["inquiryPermissionDeclaration"])
    fun inquiryPermissionDeclaration(): PermissionDeclaration = InquiryPermissions.declaration

    @Bean
    @ConditionalOnMissingBean(name = ["inquiryErasureListener"])
    fun inquiryErasureListener(repository: InquiryRepository): AccountErasureListener = InquiryErasureListener(repository)
}
