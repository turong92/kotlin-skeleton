package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 파일: 키 규칙 · OPAQUE 비밀은 프로젝트마다, 저장소는 프로젝트의 것으로 길이 나뉜다 */
class StorageProjectsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, StorageAutoConfiguration::class.java))
        .withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb")

    private val a = "skeleton.projects.a.config.storage.web"
    private val b = "skeleton.projects.b.config.storage.web"

    @Test
    fun `an OPAQUE key made in project A is not owned in project B - even by the same account id`() {
        runner.withPropertyValues("$a.key-owner=OPAQUE", "$a.key-secret=${"a".repeat(40)}", "$b.key-owner=OPAQUE", "$b.key-secret=${"b".repeat(40)}").run { ctx ->
            val scheme = StorageKeySchemes.create(ctx.getBean(StorageProperties::class.java), ctx.getBean(ProjectRegistry::class.java))
            val ownership = ctx.getBean(StorageKeyOwnership::class.java)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            val key = ProjectContext.runIn(pa) { scheme.issue("acc_1", "cat.png").also { assertTrue(ownership.owns("acc_1", it), "the ownership bean apps use knows the project") } }
            assertFalse(ProjectContext.runIn(pb) { ownership.owns("acc_1", key) }, "a key made with A's secret must not be owned in B")
        }
    }

    @Test
    fun `each project has its own key prefix and owner mode`() {
        runner.withPropertyValues("$a.key-prefix=a-files", "$b.key-prefix=b-files").run { ctx ->
            val scheme = StorageKeySchemes.create(ctx.getBean(StorageProperties::class.java), ctx.getBean(ProjectRegistry::class.java))
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            assertTrue(ProjectContext.runIn(pa) { scheme.issue("acc_1", "x.png") }.startsWith("a-files/acc_1/"))
            assertTrue(ProjectContext.runIn(pb) { scheme.issue("acc_1", "x.png") }.startsWith("b-files/acc_1/"))
            assertFailsWith<NoProjectContextException> { scheme.issue("acc_1", "x.png") }
        }
    }

    @Test
    fun `two projects with the same OPAQUE secret stop the start - and a missing or short secret names its project`() {
        val same = "s".repeat(40)
        runner.withPropertyValues("$a.key-owner=OPAQUE", "$a.key-secret=$same", "$b.key-owner=OPAQUE", "$b.key-secret=$same").run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "'a'" in it && "'b'" in it && "key-secret" in it }, messages.toString())
        }
        runner.withPropertyValues("$a.key-owner=OPAQUE", "$a.key-secret=short").run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "project 'a'" in it && "key-secret" in it }, messages.toString())
        }
    }

    private class Recording : PresignedStorage {
        val deleted = mutableListOf<ObjectKey>()
        override fun metadata(key: ObjectKey) = null
        override fun delete(key: ObjectKey) { deleted += key }
        override fun upload(request: UploadObjectRequest) = StoredObjectWriteResult(request.key)
        override fun copy(request: CopyObjectRequest) = StoredObjectWriteResult(request.destinationKey)
        override fun list(request: ListObjectsRequest) = ListedObjects(emptyList())
        override fun presignUpload(request: PresignedUploadRequest) = PresignedUrl(request.key, "PUT", URI("https://x"), expiresAt = Instant.EPOCH)
        override fun presignDownload(request: PresignedDownloadRequest) = PresignedUrl(request.key, "GET", URI("https://x"), expiresAt = Instant.EPOCH)
        override fun startMultipartUpload(request: StartMultipartUploadRequest) = StartedMultipartUpload(request.key, "u")
        override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) = error("unused")
        override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) = error("unused")
        override fun abortMultipartUpload(request: AbortMultipartUploadRequest) = Unit
    }

    @Test
    fun `the routed storage calls the storage of the project in context and refuses to guess without one`() {
        val registry = ProjectRegistry.from(
            org.springframework.core.env.StandardEnvironment().apply {
                propertySources.addFirst(org.springframework.core.env.MapPropertySource("t", mapOf("skeleton.projects.a.hosts" to "a.test", "skeleton.projects.a.schema" to "pa", "skeleton.projects.b.hosts" to "b.test", "skeleton.projects.b.schema" to "pb")))
            },
        )
        val (pa, pb) = registry.projects
        val storages = mapOf("a" to Recording(), "b" to Recording())
        val c = ProjectRegistry.from(org.springframework.core.env.StandardEnvironment().apply { propertySources.addFirst(org.springframework.core.env.MapPropertySource("t", mapOf("skeleton.projects.c.hosts" to "c.test", "skeleton.projects.c.schema" to "pc"))) }).projects.single()
        val routed = ProjectRoutedStorage(dev.sumin.skeleton.common.project.ProjectScoped { java.util.Optional.ofNullable(storages[it.id]) })
        ProjectContext.runIn(pa) { routed.delete(ObjectKey("k")) }
        ProjectContext.runIn(pb) { routed.deleteAll(listOf(ObjectKey("k1"), ObjectKey("k2"))) }
        assertEquals(listOf("k"), storages.getValue("a").deleted.map { it.value })
        assertEquals(listOf("k1", "k2"), storages.getValue("b").deleted.map { it.value })
        assertFailsWith<NoProjectContextException> { routed.delete(ObjectKey("k")) }
        val e = assertFailsWith<IllegalStateException> { ProjectContext.runIn(c) { routed.delete(ObjectKey("k")) } }
        assertTrue("'c'" in e.message!!, e.message)
    }

    @Test
    fun `a shared bucket with a project prefix keeps every key under the prefix and never shows it`() {
        val fake = Recording()
        val storage = KeyPrefixedPresignedStorage(fake, "proj-a/")
        assertEquals("k/x.png", storage.upload(UploadObjectRequest(ObjectKey("k/x.png"), byteArrayOf(1))).key.value)
        storage.delete(ObjectKey("k/x.png"))
        storage.deleteAll(listOf(ObjectKey("k/y.png")))
        assertEquals(listOf("proj-a/k/x.png", "proj-a/k/y.png"), fake.deleted.map { it.value })
        assertEquals("k/x.png", storage.presignDownload(PresignedDownloadRequest(ObjectKey("k/x.png"))).key.value)
        assertEquals("k/new.png", storage.copy(CopyObjectRequest(ObjectKey("k/x.png"), ObjectKey("k/new.png"))).key.value)
    }
}
