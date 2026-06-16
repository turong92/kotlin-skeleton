package dev.sumin.skeleton.crypto

import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

data class OpaqueUrlTokenPayload(
    val value: String,
    val purpose: String,
    val expiresAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
    val version: String = VERSION,
) {
    init {
        require(value.isNotBlank()) { "Opaque URL token value must not be blank." }
        require(purpose.isNotBlank()) { "Opaque URL token purpose must not be blank." }
        require(version == VERSION) { "Opaque URL token version '$version' is not supported." }
    }

    companion object {
        const val VERSION = "v1"
    }
}

class OpaqueUrlTokenCodec(
    private val textEncryptor: TextEncryptor,
    private val clock: Clock = Clock.systemUTC(),
    private val objectMapper: ObjectMapper = defaultObjectMapper(),
) {
    fun encode(payload: OpaqueUrlTokenPayload): String {
        val json = objectMapper.writeValueAsString(payload)
        val cipherText = textEncryptor.encrypt(json)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cipherText.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(
        token: String,
        expectedPurpose: String? = null,
    ): OpaqueUrlTokenPayload {
        if (token.isBlank()) throw CryptoException("Opaque URL token must not be blank.")

        val cipherText = runCatching {
            Base64.getUrlDecoder().decode(token).toString(StandardCharsets.UTF_8)
        }.getOrElse { throw CryptoException("Opaque URL token is not valid base64url.", it) }

        val payload = runCatching {
            objectMapper.readValue(textEncryptor.decrypt(cipherText), OpaqueUrlTokenPayload::class.java)
        }.getOrElse { throw CryptoException("Opaque URL token cannot be decoded.", it) }

        if (expectedPurpose != null && payload.purpose != expectedPurpose) {
            throw CryptoException("Opaque URL token purpose '${payload.purpose}' does not match expected purpose '$expectedPurpose'.")
        }
        if (payload.expiresAt != null && !payload.expiresAt.isAfter(clock.instant())) {
            throw CryptoException("Opaque URL token has expired.")
        }
        return payload
    }

    companion object {
        fun defaultObjectMapper(): ObjectMapper =
            JsonMapper.builder()
                .addModule(kotlinModule())
                .build()
    }
}
