package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 공개 영역: 어느 프로젝트든 켜졌으면 동작하고, 앱 단에서 켜도 꺼진 프로젝트는 복사도 주소도 없다 */
class StoragePublicProjectsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, StorageAutoConfiguration::class.java))
        .withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb")

    private val a = "skeleton.projects.a.config.storage.web"
    private val b = "skeleton.projects.b.config.storage.web"
    private val secretA = "a".repeat(40)

    private class Recording : PresignedStorage by FakePresignedStorage() {
        val copies = mutableListOf<CopyObjectRequest>()
        override fun metadata(key: ObjectKey) = StoredObjectMetadata(key, contentType = "image/png")
        override fun copy(request: CopyObjectRequest): StoredObjectWriteResult { copies += request; return StoredObjectWriteResult(request.destinationKey) }
    }

    @Test
    fun `switched on in one project only - it works there, the other project has nothing, and outside a project the answer is 'any'`() {
        runner.withPropertyValues("$a.public.enabled=true", "$a.public.secret=$secretA", "$a.public.base-url=/files").run { ctx ->
            assertEquals(null, ctx.startupFailure)
            val scheme = StorageKeySchemes.create(ctx.getBean(StorageProperties::class.java), ctx.getBean(ProjectRegistry::class.java))
            val storage = Recording()
            val uploads = StoragePublicUploads(storage, scheme)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            assertTrue(uploads.enabled(), "outside a project: some project has it on")
            assertTrue(ProjectContext.runIn(pa) { uploads.enabled() })
            assertFalse(ProjectContext.runIn(pb) { uploads.enabled() }, "project b never switched it on")
            assertTrue(ProjectContext.runIn(pa) { uploads.publish(ProjectContext.runIn(pa) { scheme.issue("acc_1", "x.png") }) })
            assertFalse(ProjectContext.runIn(pb) { uploads.publish("uploads/o/x.png") }, "no copy in b")
            assertEquals(1, storage.copies.size)
        }
    }

    @Test
    fun `switched on for the whole app but off in one project - that project gets no copy and no address worth giving`() {
        runner.withPropertyValues("skeleton.storage.web.public.enabled=true", "skeleton.storage.web.public.secret=$secretA", "skeleton.storage.web.public.base-url=/files", "$b.public.enabled=false").run { ctx ->
            assertEquals(null, ctx.startupFailure)
            val scheme = StorageKeySchemes.create(ctx.getBean(StorageProperties::class.java), ctx.getBean(ProjectRegistry::class.java))
            val storage = Recording()
            val uploads = StoragePublicUploads(storage, scheme)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            assertTrue(ProjectContext.runIn(pa) { uploads.enabled() && uploads.mayPublish("uploads/o/x.png") })
            assertFalse(ProjectContext.runIn(pb) { uploads.enabled() })
            assertFalse(ProjectContext.runIn(pb) { uploads.mayPublish("uploads/o/x.png") }, "no address is handed out in b")
            assertFalse(ProjectContext.runIn(pb) { uploads.publish("uploads/o/x.png") })
            assertTrue(storage.copies.isEmpty())
        }
    }

    @Test
    fun `a project that turns the Cloudflare purge on must write its own zone and token - inheriting them stops the start`() {
        val common = arrayOf("skeleton.storage.web.public.enabled=true", "skeleton.storage.web.public.secret=${"c".repeat(40)}", "skeleton.storage.web.public.base-url=https://cdn.example.com/app")
        // 공통의 토큰을 물려받으면 실패 (각 프로젝트의 공개 비밀은 따로 둔다)
        runner.withPropertyValues(
            *common, "$a.public.secret=$secretA", "$b.public.secret=${"b".repeat(40)}",
            "skeleton.storage.web.public.purge.cloudflare.enabled=true", "skeleton.storage.web.public.purge.cloudflare.zone-id=z", "skeleton.storage.web.public.purge.cloudflare.api-token=t",
        ).run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "inherited" in it && "purge.cloudflare" in it && "project '" in it }, messages.toString())
        }
        // 프로젝트마다 자기 것을 적으면 뜬다
        runner.withPropertyValues(
            *common,
            "$a.public.secret=$secretA", "$a.public.purge.cloudflare.enabled=true", "$a.public.purge.cloudflare.zone-id=za", "$a.public.purge.cloudflare.api-token=ta",
            "$b.public.secret=${"b".repeat(40)}", "$b.public.purge.cloudflare.enabled=true", "$b.public.purge.cloudflare.zone-id=zb", "$b.public.purge.cloudflare.api-token=tb",
        ).run { ctx -> assertEquals(null, ctx.startupFailure) }
    }
}
