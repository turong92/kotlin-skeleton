package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class BaseUrlStoragePublicUrlResolver(
    baseUrl: String,
) : StoragePublicUrlResolver {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    init {
        require(normalizedBaseUrl.isNotBlank()) { "Public URL base URL must not be blank." }
        URI.create(normalizedBaseUrl)
    }

    override fun publicUrl(key: ObjectKey): URI =
        URI.create("$normalizedBaseUrl/${encodeKey(key)}")

    private fun encodeKey(key: ObjectKey): String =
        key.value.split('/')
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20")
            }
}
