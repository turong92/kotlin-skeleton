package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.storage.web.StorageErrorCode
import dev.sumin.skeleton.storage.web.StorageValidationIssue
import java.time.Clock

/** 비회원 업로드 발급의 캡차 확인 고리 - `captcha-turnstile` 이 있으면 그 검증기로 이어진다. 켜는 것은 `guest.captcha=REQUIRED` */
fun interface StorageGuestCaptcha {
    fun verify(token: String?, ip: String?): Boolean
}

/**
 * 비회원 이미지 업로드 발급의 규칙 한 곳: 켜져 있는가 -> IP 별 한도 -> 파일 검사(이미지 형식 · 크기 · 확장자와 Content-Type 일치) -> 캡차 -> 키.
 * 한도는 먼저 센다 - 거절된 요청도 한도를 먹어 캡차 검증기 · 검사를 두들기는 일을 막는다.
 */
class StorageGuestUploads(
    private val keys: StorageKeyScheme,
    private val limiter: () -> RateLimitStore,
    private val captcha: () -> StorageGuestCaptcha?,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 통과하면 발급한 키, 아니면 [ApplicationException] (꺼짐 401 · 한도 429 · 파일 400 · 캡차 400) */
    fun issue(candidate: StorageCandidateInput, captchaToken: String?, ip: String?, limitKey: String): ObjectKey {
        val guest = keys.properties().guest
        if (!guest.enabled) throw ApplicationException("Authentication required", StorageErrorCode.UNAUTHENTICATED)

        val decision = limiter().consume("storage:guest-upload:$limitKey", guest.rateLimit.capacity, guest.rateLimit.window.toMillis(), clock.instant())
        if (!decision.allowed) throw ApplicationException("Too many uploads", StorageErrorCode.RATE_LIMITED)

        val issues = check(candidate, guest)
        if (issues.isNotEmpty()) {
            throw ApplicationException(
                message = "File rejected: " + issues.joinToString("; ") { it.code },
                errorCode = StorageErrorCode.FILE_REJECTED,
                data = mapOf("errors" to issues),
            )
        }
        if (guest.captcha == StorageGuestCaptchaMode.REQUIRED) {
            val verifier = captcha() ?: throw ApplicationException("Captcha is required but no verifier is available", StorageErrorCode.CAPTCHA_FAILED)
            if (captchaToken.isNullOrBlank() || !verifier.verify(captchaToken, ip)) throw ApplicationException("Captcha failed", StorageErrorCode.CAPTCHA_FAILED)
        }
        return ObjectKey(keys.issueGuest(candidate.contentType!!))
    }

    private fun check(c: StorageCandidateInput, guest: StorageGuestProperties): List<StorageValidationIssue> {
        val issues = mutableListOf<StorageValidationIssue>()
        val type = c.contentType?.trim()?.lowercase()
        val allowed = guest.allowedContentTypes.map { it.trim().lowercase() }.toSet()
        if (type == null || type !in allowed) {
            issues += StorageValidationIssue("UNSUPPORTED_CONTENT_TYPE", "Content type '${c.contentType.orEmpty()}' is not allowed.")
        } else {
            val ext = c.fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (ext !in StorageGuestProperties.EXTENSION_ALIASES.getValue(type)) {
                issues += StorageValidationIssue("EXTENSION_CONTENT_TYPE_MISMATCH", "File extension for '${c.fileName}' does not match the content type.")
            }
        }
        if (c.sizeBytes <= 0 || c.sizeBytes > guest.maxSizeBytes) {
            issues += StorageValidationIssue("SIZE_TOO_LARGE", "File size ${c.sizeBytes} must be 1..${guest.maxSizeBytes}.")
        }
        return issues
    }
}

data class StorageCandidateInput(val fileName: String, val contentType: String?, val sizeBytes: Long)
