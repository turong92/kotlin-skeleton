package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploadImage
import dev.sumin.skeleton.common.upload.PublicUploads
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.common.upload.UploadKeyOwnership
import dev.sumin.skeleton.common.upload.UploadVariantsPendingException
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 남에게 보이는 아바타 - [url] 공개 주소(공개 영역이 켜졌고 복사가 끝났을 때만), [image] 변환이 끝난 이미지(`thumb` · `display` 주소),
 * [pending] 변환 중이라 아직 주소가 없다(잠시 뒤 다시 읽는다). 셋 다 비면 보일 공개 모습이 없다(공개 영역 꺼짐 · 공개할 수 없는 형식) - 공개 영역이 꺼지면 서명 읽기도 꺼지므로 사진 기능은 키만 저장한다.
 */
data class AvatarView(val url: String? = null, val image: PublicUploadImage? = null, val pending: Boolean = false)

/** 짧게 읽는 서명 주소 */
data class SignedAvatar(val url: String, val expiresInSeconds: Long)

/**
 * 아바타 키 하나를 다루는 규칙 - 받기([accept]) · 공개 복사([publish]) · 보이기([view]) · 서명 주소([signed]) · 공개 영역 정리([retire]). 계정 아바타와 작성자 프로필 아바타가 같이 쓴다.
 * storage 를 모른다: 소유 확인 · 공개 영역 · 서명 읽기 · 이미지 변환 · 키 사용처는 platform 포트로만 받고, 부를 때마다 찾는다. 둘 다 없으면([NONE]) 기능이 꺼진 것이다.
 * 공개 사본은 board 의 하위 접두사(`b`) 밖([SEGMENT] = `a`)에 둔다 - board 의 전체 대조가 사진 사본을 고아로 지우지 않고, 같은 키를 board 첨부와 같이 써도 서로 지우지 않는다.
 */
class AccountAvatars(
    private val ownership: () -> UploadKeyOwnership? = { null },
    private val publicUploads: () -> PublicUploads? = { null },
    private val access: () -> PrivateUploadAccess? = { null },
    private val images: () -> UploadImages? = { null },
    /** 키를 쓰는 모든 곳(계정 · 프로필 · board …)의 합집합 - 같은 키를 두 곳에 두지 못하게 하고, 다른 곳이 쓰는 키의 공개 사본은 지우지 않는다. 없으면 검사하지 않는다 */
    private val usages: () -> List<UploadedKeyUsage> = { emptyList() },
) {
    /** 이 앱에서 아바타를 받을 수 있는가 (소유를 확인할 길이 있다) */
    val enabled: Boolean get() = ownership() != null

    // 공개 영역에 복사해 둔 키 (인스턴스 안 기억 - 재시작하면 읽을 때 한 번 더 복사한다. 복사는 멱등이다)
    private val published: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun uploads(): PublicUploads? = publicUploads()?.scoped(SEGMENT)

    /**
     * 받을 키를 검사한다 - 비었거나 · 너무 길거나 · 이 계정의 업로드가 아니거나 · 이미지가 아니거나(확장자 · 이미지 모듈이 거절한 것) · [current] 가 아닌데 이미 다른 곳이 쓰는 키면 400. 같은 거절이다(이유를 가르지 않는다).
     * [opaque] 면 키에 계정 id 가 들어 있는 것도 거절한다 - 작성자 프로필의 사진은 키로 두 프로필이 이어지면 안 된다(board 의 첨부와 같은 규칙).
     */
    fun accept(accountId: String, raw: String, opaque: Boolean = false, current: String? = null): String {
        val owner = ownership() ?: throw invalid()
        val key = raw.trim()
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH || !owner.owns(accountId, key)) throw invalid()
        if (opaque && key.contains(accountId)) throw invalid()
        if (key.substringAfterLast('/').substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) throw invalid()
        val state = images()?.status(key)?.state
        if (state == UploadImageState.REJECTED || state == UploadImageState.UNSUPPORTED || state == UploadImageState.FAILED) throw invalid()
        if (key != current && usages().any { it.inUse(listOf(key)).isNotEmpty() }) throw invalid()
        return key
    }

    /**
     * 쓰기가 끝난 **직후** 공개 영역에 복사한다(멱등). 변환이 끝나지 않았거나 실패하면 삼킨다 - [view] 가 `pending` 으로 알리고 다음 읽기가 다시 한다.
     * [stillCurrent] 는 복사한 뒤에 그 키가 아직 이 자리의 사진인지 다시 묻는다 - 그 사이 바뀌었으면(동시 `PATCH`) 방금 만든 사본을 지운다.
     */
    fun publish(key: String?, stillCurrent: () -> Boolean = { true }) {
        if (key != null) view(key, force = true, stillCurrent = stillCurrent)
    }

    /** [key] 가 남에게 보이는 모습 - 키가 없거나 기능이 꺼졌으면 null. 아직 안 한 복사를 한 번 해 둔다(멱등, [stillCurrent] 로 되살림을 막는다) */
    fun view(key: String?, force: Boolean = false, stillCurrent: () -> Boolean = { true }): AvatarView? {
        if (key == null || !enabled) return null
        val uploads = uploads()?.takeIf { it.enabled() && it.mayPublish(key) } ?: return AvatarView()
        val state = try {
            uploads.imagesOf(listOf(key))
        } catch (e: Exception) {
            log.warn("avatar: reading the image state failed ({})", e.javaClass.simpleName)
            return AvatarView()
        }
        if (key in state.refused) return AvatarView()
        if (key in state.pending) return AvatarView(pending = true)
        if (force || key !in published) {
            try {
                if (!uploads.publish(key)) return AvatarView()
            } catch (e: UploadVariantsPendingException) {
                return AvatarView(pending = true)
            } catch (e: Exception) {
                log.warn("avatar: copy to the public area failed ({})", e.javaClass.simpleName)
                return AvatarView()
            }
            if (!stillCurrent()) {   // 복사하는 사이 바뀌었다 - 지운 사진의 사본을 되살리지 않는다
                remove(key, force = true, swallow = true)
                return AvatarView()
            }
            if (published.size >= MAX_REMEMBERED) published.clear()
            published += key
        }
        return AvatarView(url = uploads.urlOf(key), image = state.ready[key])
    }

    /** 소유자가 자기 아바타를 볼 서명 주소 - 이미지 모듈이 있으면 `display` 변환본(변환 전이면 null), 없으면 원본. 서명 읽기가 꺼졌으면(공개 영역이 꺼지면 함께 꺼진다) null */
    fun signed(key: String): SignedAvatar? {
        val reader = access()?.takeIf { it.enabled() } ?: return null
        val ttl = reader.defaultTtl
        val module = images()
        if (module != null) {
            if (module.status(key) == null) module.ensure(key)
            if (module.status(key) != null) return module.signedVariantUrl(key, UploadImages.DISPLAY, ttl)?.let { SignedAvatar(it, ttl.seconds) }
        }
        return SignedAvatar(reader.downloadUrl(key, ttl), ttl.seconds)
    }

    /**
     * 바뀌거나 지워져 쓰이지 않는 키의 공개 복사를 지운다 (CDN 캐시는 `Cache-Control` max-age 안에 사라진다). 다른 곳이 아직 쓰는 키면 지우지 않는다. 실패해도 쓰기는 이미 끝났다 - 삼키고 로그만.
     * [force]: 이 키를 쓰던 줄이 아직 남아 있어도 지운다(탈퇴 고리 - 줄은 고리 뒤에 지워진다).
     */
    fun retire(key: String?, force: Boolean = false) {
        if (key != null) remove(key, force, swallow = true)
    }

    /** [retire] 와 같되 실패를 **던진다** - 탈퇴 고리가 쓴다(던져야 삭제 서비스가 다음 주기에 다시 시도한다) */
    fun retireOrThrow(key: String?, force: Boolean = false) {
        if (key != null) remove(key, force, swallow = false)
    }

    private fun remove(key: String, force: Boolean, swallow: Boolean) {
        try {
            if (!force && usages().filter { u -> u.answers.any { it in AVATAR_HOLDERS } }.any { it.inUse(listOf(key)).isNotEmpty() }) return
            published -= key
            uploads()?.takeIf { it.enabled() }?.unpublish(key)
        } catch (e: Exception) {
            if (!swallow) throw e
            log.warn("avatar: removing the public copy failed ({})", e.javaClass.simpleName)
        }
    }

    private fun invalid() = ApplicationException("Invalid avatarKey", PlatformErrorCode.VALIDATION_FAILED)

    companion object {
        val NONE = AccountAvatars()
        const val MAX_KEY_LENGTH = 512

        /** 공개 영역의 하위 접두사 - board 는 `b` */
        const val SEGMENT = "a"

        /** 공개 사본(`a/`)을 쓰는 곳 - `UploadKeyHolder` 이름. 사본을 거둘지는 이 곳들의 사용만 본다(board 등이 같은 키를 첨부해도 `a/` 사본은 쓰지 않는다) */
        private val AVATAR_HOLDERS = setOf("account", "author-profile")
        private const val MAX_REMEMBERED = 10_000

        /** 사진으로 받는 확장자 - 공개 영역이 이미지로 다루는 것과 같다 (PDF 등은 받지 않는다) */
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic")
        private val log = LoggerFactory.getLogger(AccountAvatars::class.java)
    }
}
