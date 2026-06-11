package dev.sumin.skeleton.idempotency

import jakarta.servlet.http.HttpServletRequest
import java.security.MessageDigest

object IdempotencyFingerprint {
    fun calculate(
        request: HttpServletRequest,
        body: ByteArray,
    ): String {
        val canonicalRequest = buildString {
            append(request.method.uppercase())
            append('\n')
            append(request.requestURI)
            append('\n')
            append(request.queryString.orEmpty())
            append('\n')
            append(request.contentType.orEmpty())
            append('\n')
        }.toByteArray(Charsets.UTF_8)

        return sha256(canonicalRequest + body)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}
