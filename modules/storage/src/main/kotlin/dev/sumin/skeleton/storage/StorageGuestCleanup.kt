package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import org.slf4j.LoggerFactory

/**
 * 비회원 업로드 중 [StorageGuestProperties.GuestCleanup.ttl] 안에 글 · 댓글에 붙지 않은 것을 지운다 (`skeleton.storage.web.guest.cleanup.enabled=true` 일 때만 주기 작업이 부른다).
 * 지우는 조건: 키가 비회원 꼴이고 MAC 이 맞으며(남이 둔 파일은 건드리지 않는다), 만든 지 ttl 이 지났고, [UploadedKeyUsage] 가 사용 중이라고 하지 않은 것.
 * [UploadedKeyUsage] 구현이 없으면 아무것도 지우지 않는다. 한 번에 [StorageGuestProperties.GuestCleanup.batchSize] 개까지 - 남은 것은 다음 틱에.
 * 프로젝트 모드에서는 프로젝트마다 그 프로젝트의 설정 · 저장소로 돈다.
 */
class StorageGuestCleanup(
    private val storage: ObjectStorage,
    private val keys: StorageKeyScheme,
    private val usage: () -> UploadedKeyUsage?,
    private val time: TimeProvider,
    private val projects: ProjectRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): Int {
        val inUse = usage() ?: run {
            log.warn("storage guest cleanup is on but no UploadedKeyUsage bean exists - nothing is deleted")
            return 0
        }
        var deleted = 0
        if (projects == null) deleted += cleanOne(inUse) else projects.forEachProject { deleted += cleanOne(inUse) }
        return deleted
    }

    private fun cleanOne(inUse: UploadedKeyUsage): Int {
        val web = keys.properties()
        val cleanup = web.guest.cleanup
        if (!web.guest.enabled || !cleanup.enabled) return 0
        val cutoff = time.now().minus(cleanup.ttl)
        val prefix = web.keyPrefix.trim('/') + "/g/"
        val stale = mutableListOf<ObjectKey>()
        var token: String? = null
        do {
            val page = storage.list(ListObjectsRequest(prefix, token, 1000))
            page.objects.filter { it.lastModified?.isBefore(cutoff) == true && keys.isGuestKey(it.key.value) }.forEach { stale += it.key }
            token = page.nextContinuationToken.takeIf { page.truncated }
        } while (token != null && stale.size < cleanup.batchSize)
        val candidates = stale.take(cleanup.batchSize)
        if (candidates.isEmpty()) return 0
        val used = inUse.inUse(candidates.map { it.value })
        val orphans = candidates.filter { it.value !in used }
        if (orphans.isNotEmpty()) storage.deleteAll(orphans)
        return orphans.size
    }
}
