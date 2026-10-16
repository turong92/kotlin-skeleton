package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.project.ProjectScoped
import java.net.URI

/**
 * 프로젝트 모드의 저장소 — 부를 때마다 **지금 프로젝트의** 저장소로 넘긴다(프로젝트마다 버킷 · 자격 증명 · 공개 URL 기준이 다르다).
 * 프로젝트 없이 부르면 `NoProjectContextException` — 아무 버킷이나 고르지 않는다. 저장소를 정하지 않은 프로젝트(빈 Optional)가 쓰면 그 프로젝트 이름을 담은 예외다.
 */
class ProjectRoutedStorage(private val parts: ProjectScoped<java.util.Optional<PresignedStorage>>) : PresignedStorage {
    private val target: PresignedStorage
        get() = parts.get().orElseThrow {
            IllegalStateException("Project '${dev.sumin.skeleton.common.project.ProjectContext.current().id}' has no file storage configured (skeleton.projects.<id>.config.storage-s3.bucket)")
        }

    override fun metadata(key: ObjectKey) = target.metadata(key)
    override fun delete(key: ObjectKey) = target.delete(key)
    override fun deleteAll(keys: Collection<ObjectKey>) = target.deleteAll(keys)
    override fun publicUrl(key: ObjectKey): URI? = target.publicUrl(key)
    override fun upload(request: UploadObjectRequest) = target.upload(request)
    override fun copy(request: CopyObjectRequest) = target.copy(request)
    override fun move(request: MoveObjectRequest) = target.move(request)
    override fun list(request: ListObjectsRequest) = target.list(request)
    override fun presignUpload(request: PresignedUploadRequest) = target.presignUpload(request)
    override fun presignDownload(request: PresignedDownloadRequest) = target.presignDownload(request)
    override fun startMultipartUpload(request: StartMultipartUploadRequest) = target.startMultipartUpload(request)
    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) = target.presignMultipartUploadPart(request)
    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) = target.completeMultipartUpload(request)
    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) = target.abortMultipartUpload(request)
}

/**
 * 같은 버킷을 여럿이 나눠 쓸 때 한 쪽의 키 전부를 [prefix] 아래에 둔다 — 안에서는 접두사 없는 키로 말하고(요청 · 결과 모두), 버킷에는 `<prefix><키>` 로 들어간다.
 * 공개 URL 은 저장소가 실제 키(접두사 포함)로 만든 것이라 그대로 둔다(RAW 면 `<기준 주소>/<접두사><키>`).
 */
class KeyPrefixedPresignedStorage(private val delegate: PresignedStorage, prefix: String) : PresignedStorage {
    private val prefix = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    private fun real(key: ObjectKey) = ObjectKey(prefix + key.value)
    private fun plain(key: ObjectKey) = ObjectKey(key.value.removePrefix(prefix))

    override fun metadata(key: ObjectKey) = delegate.metadata(real(key))?.let { it.copy(key = plain(it.key)) }
    override fun delete(key: ObjectKey) = delegate.delete(real(key))
    override fun deleteAll(keys: Collection<ObjectKey>) = delegate.deleteAll(keys.map(::real))
    override fun publicUrl(key: ObjectKey): URI? = delegate.publicUrl(real(key))

    override fun upload(request: UploadObjectRequest) = delegate.upload(request.copy(key = real(request.key))).let { it.copy(key = plain(it.key)) }
    override fun copy(request: CopyObjectRequest) =
        delegate.copy(request.copy(sourceKey = real(request.sourceKey), destinationKey = real(request.destinationKey))).let { it.copy(key = plain(it.key)) }
    override fun move(request: MoveObjectRequest) =
        delegate.move(request.copy(sourceKey = real(request.sourceKey), destinationKey = real(request.destinationKey))).let { it.copy(key = plain(it.key)) }
    override fun list(request: ListObjectsRequest): ListedObjects {
        val listed = delegate.list(request.copy(prefix = prefix + request.prefix))
        return listed.copy(objects = listed.objects.map { it.copy(key = plain(it.key)) })
    }

    override fun presignUpload(request: PresignedUploadRequest) = delegate.presignUpload(request.copy(key = real(request.key))).let { it.copy(key = plain(it.key)) }
    override fun presignDownload(request: PresignedDownloadRequest) = delegate.presignDownload(request.copy(key = real(request.key))).let { it.copy(key = plain(it.key)) }
    override fun startMultipartUpload(request: StartMultipartUploadRequest) = delegate.startMultipartUpload(request.copy(key = real(request.key))).let { it.copy(key = plain(it.key)) }
    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) =
        delegate.presignMultipartUploadPart(request.copy(key = real(request.key))).let { it.copy(key = plain(it.key)) }
    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) =
        delegate.completeMultipartUpload(request.copy(key = real(request.key))).let { it.copy(key = plain(it.key)) }
    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) = delegate.abortMultipartUpload(request.copy(key = real(request.key)))
}
