package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.video.Video
import dev.sumin.skeleton.video.VideoAccessPolicy
import dev.sumin.skeleton.video.VideoVisibility
import org.springframework.stereotype.Component

/**
 * 이 앱의 영상 정책: 영상은 노트에 붙이는 한 가지 용도(`note`)이고 늘 비공개다 - 노트는 주인만 보므로 영상도 주인만 올리고 · 보고 · 지운다.
 * 모듈의 기본 정책은 공개 영상을 아무나 틀게 두므로, 공개 영상을 받지 않는다는 이 앱의 선택을 여기서 못 박는다.
 */
@Component
class NoteVideoPolicy : VideoAccessPolicy {
    override fun canUpload(caller: String, purpose: String, visibility: VideoVisibility) = purpose == PURPOSE && visibility == VideoVisibility.PRIVATE

    override fun canPlay(caller: String?, video: Video) = caller != null && caller == video.ownerAccountId

    override fun canManage(caller: String, video: Video) = caller == video.ownerAccountId

    companion object {
        const val PURPOSE = "note"
    }
}
