package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.time.asClock
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import java.time.Duration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
) {
    init {
        require(perClient >= 1 && perAccount >= 1) { "skeleton.inquiry.per-client / per-account must be at least 1" }
        require(!window.isNegative && !window.isZero) { "skeleton.inquiry.window must be positive" }
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
