package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.upload.UploadImageState

/**
 * 상태 줄의 변환본 하나 - 키는 저장하지 않고 원본 키에서 계산한다. [ext] 가 [ORIGINAL] 이면 변환본이 아니라 원본을 가리킨다(움직이는 이미지의 `display`).
 * [alt] 는 같은 그림의 다른 형식 확장자들(크기는 같다, 예 `["webp"]`) - 소문자 영문뿐
 */
data class StoredVariant(val width: Int, val height: Int, val ext: String, val alt: List<String> = emptyList()) {
    companion object {
        const val ORIGINAL = "-"
    }
}

/** 이미지 원본 하나당 한 줄. [keyHash] 는 원본 키의 sha256(hex 64) */
data class ImageRow(
    val keyHash: String,
    val objectKey: String,
    val state: UploadImageState,
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val animated: Boolean = false,
    val variants: Map<String, StoredVariant> = emptyMap(),
    val reason: String? = null,
)

/** 이미지 상태 저장소 - `storage-image-jdbc` 가 구현한다. 인메모리 구현은 없다(여러 인스턴스가 같은 줄을 봐야 한다) */
interface ImageStatusStore {
    fun find(keyHash: String): ImageRow?

    fun findAll(keyHashes: Collection<String>): Map<String, ImageRow>

    /**
     * 줄이 없으면 `PENDING` 줄을 만든다. **이 호출이 줄을 만들었을 때만** 같은 트랜잭션 안에서 [onCreated](작업 넣기)를 부르고 true 를 돌려준다 -
     * 동시에 온 호출이 여럿이어도 줄 하나 · 작업 하나다.
     */
    fun register(keyHash: String, objectKey: String, onCreated: () -> Unit): Boolean

    /** `PENDING` -> `READY`. 줄이 없거나 이미 `PENDING` 이 아니면 false */
    fun markReady(keyHash: String, width: Int, height: Int, format: String, animated: Boolean, variants: Map<String, StoredVariant>): Boolean

    /** `PENDING` -> `REJECTED` · `UNSUPPORTED` · `FAILED`. [reason] 은 사용자에게 보여도 되는 코드 */
    fun markTerminal(keyHash: String, state: UploadImageState, reason: String, width: Int? = null, height: Int? = null): Boolean

    fun delete(keyHashes: Collection<String>)
}

/** 변환 작업을 큐에 넣는다 - 호출자 트랜잭션 안에서만 부른다([ImageStatusStore.register] 의 `onCreated`) */
fun interface ImageVariantTasks {
    fun enqueue(keyHash: String)
}
