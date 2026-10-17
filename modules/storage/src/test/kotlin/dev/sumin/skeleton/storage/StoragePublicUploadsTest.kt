package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StoragePublicUploadsTest {
    private fun web(publicSecret: String = "public-secret-for-tests-0123456789abcdef", prefix: String = "public") = StorageWebProperties(
        keyOwner = StorageKeyOwner.OPAQUE, keySecret = "x".repeat(32),
        `public` = StoragePublicProperties(enabled = true, secret = publicSecret, prefix = prefix, baseUrl = "https://cdn.example.com/"),
    )

    private class Recording : PresignedStorage by FakePresignedStorage() {
        val copies = mutableListOf<CopyObjectRequest>()
        val deleted = mutableListOf<String>()
        override fun copy(request: CopyObjectRequest): StoredObjectWriteResult { copies += request; return StoredObjectWriteResult(request.destinationKey) }
        override fun delete(key: ObjectKey) { deleted += key.value }
    }

    @Test
    fun `the public key is computed from the private key and shows neither the account nor the original key`() {
        val scheme = StorageKeyScheme(web())
        val privateKey = scheme.issue("acc_secret_account", "photo.PNG")
        val publicKey = scheme.publicKeyOf(privateKey)
        assertTrue(Regex("public/[0-9a-f]{32}\\.png").matches(publicKey), publicKey)
        assertFalse("acc_secret_account" in publicKey)
        assertFalse(privateKey.substringAfter("uploads/") in publicKey, "the private key's parts do not leak")
        assertEquals(publicKey, scheme.publicKeyOf(privateKey), "same input, same key - so it can be deleted without a lookup table")
        assertNotEquals(publicKey, StorageKeyScheme(web(publicSecret = "another-public-secret-0123456789abcdef")).publicKeyOf(privateKey), "not guessable without the secret")
    }

    @Test
    fun `nobody owns the public area, and its prefix cannot overlap the upload prefix`() {
        val scheme = StorageKeyScheme(web())
        assertFalse(scheme.owns("acc_a", scheme.publicKeyOf(scheme.issue("acc_a", "a.png"))))
        assertFailsWith<IllegalArgumentException> { web(prefix = "uploads/pub") }
        assertFailsWith<IllegalArgumentException> { StoragePublicProperties(enabled = true, secret = "short", baseUrl = "x") }
        assertFailsWith<IllegalArgumentException> { StoragePublicProperties(enabled = true, secret = "public-secret-for-tests-0123456789abcdef") }   // base-url 없음
    }

    @Test
    fun `publish copies with the cache header, unpublish deletes the copy and tells the purger`() {
        val storage = Recording()
        val purged = mutableListOf<String>()
        val scheme = StorageKeyScheme(web())
        val uploads = StoragePublicUploads(storage, scheme) { PublicUploadPurger { purged += it } }
        val key = scheme.issue("acc_a", "a.png")
        uploads.publish(key)
        val copy = storage.copies.single()
        assertEquals(key, copy.sourceKey.value)
        assertEquals(scheme.publicKeyOf(key), copy.destinationKey.value)
        assertEquals("public, max-age=3600", copy.cacheControl)
        assertEquals("image/png", copy.contentType)
        assertEquals("https://cdn.example.com/" + scheme.publicKeyOf(key), uploads.urlOf(key))
        uploads.unpublish(key)
        assertEquals(listOf(scheme.publicKeyOf(key)), storage.deleted)
        assertEquals(listOf(uploads.urlOf(key)), purged)
    }
}
