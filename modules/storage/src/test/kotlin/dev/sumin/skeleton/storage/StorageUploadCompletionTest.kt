package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.storage.web.StorageErrorCode
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageUploadCompletionTest {
    private val storage = FakePresignedStorage()
    private val accepted = mutableListOf<InspectedUpload>()
    private val listener = UploadCompletionListener { accepted += it }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun completion(vararg inspectors: UploadInspector) = StorageUploadCompletion(storage, { inspectors.toList() }, { listOf(listener) })
    private fun put(key: String, bytes: ByteArray, declared: String) { storage.objects[key] = bytes to declared }

    @Test
    fun `html svg and pdf bytes declared as image png are refused, and only that one object is deleted`() {
        put("uploads/acc_a/keep/keep.png", png, "image/png")
        val payloads = mapOf(
            "html" to "<!DOCTYPE html><html><script>alert(1)</script></html>".toByteArray(),
            "svg" to """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>""".toByteArray(),
            "pdf" to "%PDF-1.7\n1 0 obj<<>>endobj".toByteArray(),
        )
        payloads.forEach { (name, bytes) ->
            val key = "uploads/acc_a/$name/evil.png"
            put(key, bytes, "image/png")
            val failure = assertFailsWith<ApplicationException>(name) { completion().complete(ObjectKey(key), 1_000_000) }
            assertEquals(StorageErrorCode.FILE_REJECTED, failure.errorCode, name)
            assertFalse(key in storage.objects, "$name: the refused object is deleted")
        }
        assertTrue("uploads/acc_a/keep/keep.png" in storage.objects, "another object is never touched")
        assertEquals(payloads.keys.map { "uploads/acc_a/$it/evil.png" }, storage.deleted)
        assertTrue(accepted.isEmpty(), "nothing downstream hears about a refused upload")
    }

    @Test
    fun `an inspector's refusal also deletes the object, and a clean upload reaches the listeners with the sniffed type`() {
        put("uploads/acc_a/ok/ok.png", png, "image/png")
        val deny = UploadInspector { InspectionVerdict.Reject("VIRUS_FOUND", "no") }
        val failure = assertFailsWith<ApplicationException> { completion(deny).complete(ObjectKey("uploads/acc_a/ok/ok.png"), 1_000_000) }
        assertEquals(StorageErrorCode.FILE_REJECTED, failure.errorCode)
        assertFalse("uploads/acc_a/ok/ok.png" in storage.objects)

        put("uploads/acc_a/ok2/ok.png", png, "image/png")
        val result = completion().complete(ObjectKey("uploads/acc_a/ok2/ok.png"), 1_000_000)
        assertEquals("image/png", result.contentType)
        assertEquals(png.size.toLong(), result.sizeBytes)
        assertEquals(listOf("uploads/acc_a/ok2/ok.png"), accepted.map { it.key.value })
        assertEquals("image/png", accepted.single().detectedContentType)
        assertTrue("uploads/acc_a/ok2/ok.png" in storage.objects)
    }
}
