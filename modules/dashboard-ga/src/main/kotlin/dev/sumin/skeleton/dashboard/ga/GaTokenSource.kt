package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import tools.jackson.databind.json.JsonMapper

/** 서비스 계정 키가 쓸 수 없거나 토큰을 못 받았다. 메시지에 키 · 토큰 · 응답 본문을 싣지 않는다 */
class GaAuthException(message: String) : RuntimeException(message)

/**
 * 서비스 계정 키 → RS256 JWT → OAuth 접근 토큰 (라이브러리 없이 `java.security`). 토큰은 만료 1분 전까지 재사용한다 — 키마다(프로젝트마다 키가 다르다) 한 칸이고,
 * 같은 키로 동시에 와도 토큰 요청은 한 번이다.
 */
class GaTokenSource(private val http: ExternalHttpClient, private val now: () -> Instant = Instant::now) {
    private class Entry(val token: String, val expiresAt: Instant)

    private val tokens = ConcurrentHashMap<String, Entry>()
    private val mapper = JsonMapper.builder().build()

    fun token(properties: GaProperties): String {
        val id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(properties.credentials.toByteArray()))
        return tokens.compute(id) { _, current ->
            if (current != null && current.expiresAt.isAfter(now().plus(REUSE_MARGIN))) current else fetch(properties)
        }!!.token
    }

    private fun fetch(properties: GaProperties): Entry {
        val issued = now()
        val assertion = try { assertion(properties, issued) } catch (e: GaAuthException) { throw e } catch (e: Exception) { throw GaAuthException("The Google service account key cannot be used") }
        val target = URI.create(properties.tokenUrl)
        val base = "${target.scheme}://${target.rawAuthority}"
        val response = http.postForm(
            properties.clientName,
            target.rawPath.ifBlank { "/" },
            mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion" to assertion),
            String::class.java,
        ) {
            baseUrl(base)
            timeout(properties.timeout)
            loggingTag("dashboard.ga.token")
        }.block() ?: throw GaAuthException("Google returned no token response")
        val json = try { mapper.readTree(response) } catch (e: Exception) { throw GaAuthException("Google returned an unreadable token response") }
        val token = json["access_token"]?.asString()?.takeIf { it.isNotBlank() } ?: throw GaAuthException("Google returned no access token")
        val lifetime = json["expires_in"]?.asLong(3600) ?: 3600
        return Entry(token, issued.plusSeconds(lifetime))
    }

    private fun assertion(properties: GaProperties, issued: Instant): String {
        val key = mapper.readTree(String(Base64.getDecoder().decode(properties.credentials.trim())))
        val email = key["client_email"]?.asString()?.takeIf { it.isNotBlank() } ?: throw GaAuthException("The Google service account key has no client_email")
        val pem = key["private_key"]?.asString()?.takeIf { it.isNotBlank() } ?: throw GaAuthException("The Google service account key has no private_key")
        val der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun part(value: Map<String, Any>) = encoder.encodeToString(mapper.writeValueAsBytes(value))
        val signingInput = part(mapOf("alg" to "RS256", "typ" to "JWT")) + "." +
            part(mapOf("iss" to email, "scope" to SCOPE, "aud" to properties.tokenUrl, "iat" to issued.epochSecond, "exp" to issued.plusSeconds(3600).epochSecond))
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(signingInput.toByteArray()) }.sign()
        return signingInput + "." + encoder.encodeToString(signature)
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/analytics.readonly"
        val REUSE_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
