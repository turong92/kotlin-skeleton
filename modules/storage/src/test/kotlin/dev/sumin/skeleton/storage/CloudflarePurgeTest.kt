package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import reactor.core.publisher.Mono

/** Cloudflare 캐시 비우기 - 진짜 호출은 하지 않는다(가짜 ExternalHttpClient) */
class CloudflarePurgeTest {
    private class FakeHttp(private val reply: () -> Map<String, Any?> = { mapOf("success" to true) }) : ExternalHttpClient {
        val calls = mutableListOf<Pair<String, Any?>>()
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> post(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> {
            ExternalHttpRequestSpec().apply(customize)   // 헤더를 거는 람다가 터지지 않는지
            calls += path to body
            return Mono.fromCallable { reply() as T }
        }
        override fun <T : Any> get(clientName: String, path: String, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> postForm(clientName: String, path: String, form: Map<String, String>, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> put(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> patch(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> delete(clientName: String, path: String, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
    }

    private val cf = PublicPurgeProperties.CloudflarePurge(enabled = true, zoneId = "zone1", apiToken = "tok-secret-value")
    private fun pub(base: String = "https://cdn.example.com/app", purge: PublicPurgeProperties.CloudflarePurge = cf) =
        StoragePublicProperties(enabled = true, secret = "public-secret-for-tests-0123456789abcdef", baseUrl = base, purge = PublicPurgeProperties(purge))

    @Test
    fun `urls go out in batches of 30 to the zone's purge_cache as files`() {
        val http = FakeHttp()
        CloudflarePublicUploadPurger(http) { pub() }.purge((1..70).map { "https://cdn.example.com/app/public/b/$it.png" })
        assertEquals(listOf(30, 30, 10), http.calls.map { ((it.second as Map<*, *>)["files"] as List<*>).size })
        assertEquals("https://api.cloudflare.com/client/v4/zones/zone1/purge_cache", http.calls.first().first)
    }

    @Test
    fun `an answer without success is a failure the caller can retry, and a disabled purger does nothing`() {
        assertFailsWith<IllegalStateException> { CloudflarePublicUploadPurger(FakeHttp { mapOf("success" to false, "errors" to listOf("bad")) }) { pub() }.purge(listOf("https://x/y.png")) }
        val untouched = FakeHttp()
        CloudflarePublicUploadPurger(untouched) { pub(purge = cf.copy(enabled = false)) }.purge(listOf("https://x/y.png"))
        assertTrue(untouched.calls.isEmpty())
    }

    @Test
    fun `the token never shows in toString, and the settings are checked when purge is on`() {
        assertFalse("tok-secret-value" in cf.toString())
        assertFalse("tok-secret-value" in pub().toString() + pub().purge.toString())
        assertFailsWith<IllegalArgumentException> { cf.copy(zoneId = "") }
        assertFailsWith<IllegalArgumentException> { cf.copy(apiToken = "") }
        assertFailsWith<IllegalArgumentException> { StoragePublicProperties(enabled = false, purge = PublicPurgeProperties(cf)) }
    }

    @Test
    fun `a same-origin base-url needs the origin for purge - and then purge names the absolute cached address`() {
        assertEquals("/files/public/b/x.png", pub("/files", cf.copy(enabled = false)).baseUrl + "/public/b/x.png")   // 응답 주소는 상대 경로 그대로
        assertFailsWith<IllegalArgumentException> { pub("/files") }   // purge on, relative path, no public-origin
        val withOrigin = pub("/files", cf.copy(publicOrigin = "https://app.example.com/"))
        assertEquals("https://app.example.com/files/public/b/x.png", withOrigin.absoluteUrl("public/b/x.png"))
        assertEquals("https://cdn.example.com/app/public/b/x.png", pub().absoluteUrl("public/b/x.png"))
        assertFailsWith<IllegalArgumentException> { StoragePublicProperties(enabled = true, secret = "public-secret-for-tests-0123456789abcdef", baseUrl = "cdn.example.com") }
    }

    @Test
    fun `deleting a public object purges its absolute address after the delete, and a failed purge is reported to the caller`() {
        val order = mutableListOf<String>()
        val storage = object : PresignedStorage by dev.sumin.skeleton.storagetest.FakePresignedStorage() {
            override fun delete(key: ObjectKey) { order += "delete:${key.value}" }
        }
        val scheme = StorageKeyScheme(StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE, keySecret = "x".repeat(32), `public` = pub("/files", cf.copy(publicOrigin = "https://app.example.com"))))
        val uploads = StoragePublicUploads(storage, scheme) { PublicUploadPurger { urls -> order += "purge:${urls.single()}" } }
        uploads.deletePublicKey("public/b/a.png")
        assertEquals(listOf("delete:public/b/a.png", "purge:https://app.example.com/files/public/b/a.png"), order)
        val failing = StoragePublicUploads(storage, scheme) { PublicUploadPurger { error("cloudflare down") } }
        assertFailsWith<IllegalStateException> { failing.deletePublicKey("public/b/a.png") }
    }
}
