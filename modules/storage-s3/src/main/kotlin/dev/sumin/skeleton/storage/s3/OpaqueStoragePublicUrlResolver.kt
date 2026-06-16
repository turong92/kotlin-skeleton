package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.crypto.OpaqueUrlTokenCodec
import dev.sumin.skeleton.crypto.OpaqueUrlTokenPayload
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.net.URI

class OpaqueStoragePublicUrlResolver(
    baseUrl: String,
    tokenPathPrefix: String,
    private val tokenPurpose: String,
    private val tokenCodec: OpaqueUrlTokenCodec,
) : StoragePublicUrlResolver {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    private val normalizedTokenPathPrefix = tokenPathPrefix.trim().trim('/').let { prefix ->
        if (prefix.isBlank()) "" else "/$prefix"
    }

    init {
        require(normalizedBaseUrl.isNotBlank()) { "Opaque public URL base URL must not be blank." }
        require(tokenPurpose.isNotBlank()) { "Opaque public URL token purpose must not be blank." }
        URI.create(normalizedBaseUrl)
    }

    override fun publicUrl(key: ObjectKey): URI {
        val token = tokenCodec.encode(
            OpaqueUrlTokenPayload(
                value = key.value,
                purpose = tokenPurpose,
                metadata = mapOf("provider" to "s3"),
            ),
        )
        return URI.create("$normalizedBaseUrl$normalizedTokenPathPrefix/$token")
    }
}
