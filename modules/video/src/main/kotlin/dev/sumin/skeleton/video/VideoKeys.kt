package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.StorageWebProperties

/**
 * 영상 산출물의 저장소 키 — 영상마다 폴더 하나(`<root>/<id>/`). 공개 영상은 storage 공개 영역(`<public.prefix>/v`), 비공개 영상은 `<video.prefix>/p`.
 * 올린 원 파일(`<video.prefix>/u/<id>`)은 폴더 밖이다. 폴더 이름이 내용 해시라 산출물은 전부 `immutable` 캐시
 */
class VideoKeys(private val props: VideoProperties, private val web: StorageWebProperties) {
    fun root(visibility: VideoVisibility): String =
        if (visibility == VideoVisibility.PUBLIC) "${web.`public`.prefix.trim('/')}/v" else "${props.root}/p"

    /** 슬래시로 끝나는 영상 폴더 — 접두사 일치가 다른 영상 id 에 걸치지 않는다 */
    fun folder(video: Video): String = "${root(video.visibility)}/${video.id}/"

    fun cacheControl(video: Video): String =
        "${if (video.visibility == VideoVisibility.PUBLIC) "public" else "private"}, max-age=31536000, immutable"
}
