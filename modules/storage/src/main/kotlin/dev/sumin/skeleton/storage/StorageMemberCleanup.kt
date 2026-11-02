package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import org.slf4j.LoggerFactory

/**
 * 버려진 회원 업로드를 지운다 (`skeleton.storage.web.cleanup.enabled=true` 일 때만 주기 작업이 부른다).
 * 지우는 조건: 업로드 접두사 아래이고 · 비회원 영역(`<prefix>/g/`)이 아니고 · 만든 지 [StorageCleanupProperties.minAge] 가 지났고 · [UploadedKeyUsage] 가 사용 중이라고 하지 않은 것.
 * 공개 영역 · 변환본 접두사는 업로드 접두사와 겹치지 않으므로(기동 때 확인) 목록에 나오지 않는다. 이미지 모듈이 있으면 지운 키의 변환본 · 상태 줄도 같이 지운다.
 * 지우기 직전에 사용처를 한 번 더 묻는다(훑는 사이 붙은 키). 사용처 구현이 없으면 아무것도 지우지 않는다. 한 번에 [StorageCleanupProperties.batchSize] 개까지 - 남은 것은 다음 틱에. 프로젝트 모드에서는 프로젝트마다 그 프로젝트의 설정 · 저장소로 돈다.
 */
class StorageMemberCleanup(
    private val storage: ObjectStorage,
    private val keys: StorageKeyScheme,
    private val usage: () -> UploadedKeyUsage?,
    private val time: TimeProvider,
    private val projects: ProjectRegistry? = null,
    private val images: () -> UploadImages? = { null },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): Int {
        val inUse = usage() ?: run {
            log.warn("storage member cleanup is on but no UploadedKeyUsage bean exists - nothing is deleted")
            return 0
        }
        var deleted = 0
        if (projects == null) deleted += cleanOne(inUse) else projects.forEachProject { deleted += cleanOne(inUse) }
        return deleted
    }

    private fun cleanOne(inUse: UploadedKeyUsage): Int {
        val web = keys.properties()
        val cleanup = web.cleanup
        if (!cleanup.enabled) return 0
        val cutoff = time.now().minus(cleanup.minAge)
        val root = web.keyPrefix.trim('/') + "/"
        val guestArea = root + "g/"   // 비회원 설정이 꺼져 있어도 비회원 꼴 키는 건드리지 않는다
        val orphans = mutableListOf<ObjectKey>()
        var token: String? = null
        do {
            val page = storage.list(ListObjectsRequest(root, token, 1000))
            val old = page.objects
                .filter { it.lastModified?.isBefore(cutoff) == true && !it.key.value.startsWith(guestArea) && !keys.isGuestKey(it.key.value) }
                .map { it.key }
            if (old.isNotEmpty()) {
                val used = inUse.inUse(old.map { it.value })
                orphans += old.filter { it.value !in used }
            }
            token = page.nextContinuationToken.takeIf { page.truncated }
        } while (token != null && orphans.size < cleanup.batchSize)
        // 지우기 직전에 한 번 더 묻는다 - 훑는 사이(목록이 길면 수 초) 글 · 리뷰에 붙은 키를 지우지 않는다. 남은 틈은 이 한 번의 질의와 삭제 사이뿐이다
        val candidates = orphans.take(cleanup.batchSize)
        val usedNow = if (candidates.isEmpty()) emptySet() else inUse.inUse(candidates.map { it.value })
        val doomed = candidates.filter { it.value !in usedNow }
        if (doomed.isNotEmpty()) {
            storage.deleteAll(doomed)
            images()?.forget(doomed.map { it.value })   // 원본을 지운 뒤에 - 원본이 남은 채 변환본만 사라지지 않게
        }
        return doomed.size
    }
}
