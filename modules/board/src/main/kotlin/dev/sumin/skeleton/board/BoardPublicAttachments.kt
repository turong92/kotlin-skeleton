package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.PublicUploads
import org.slf4j.LoggerFactory

/**
 * 공개 영역(`PublicUploads`)을 board 상태에 맞춘다 - 공개 조건(글 · 댓글 PUBLISHED)에 드는 첨부는 복사해 두고, 조건에서 빠진 것은 지운다.
 * 모든 단계는 멱등이고, 실패하면 줄이 남아 주기 조정([sweep])이 다시 시도한다. **지우는 쪽이 먼저**다(공개 영역에 남으면 안 되는 쪽이 우선).
 * 복사 전에 「복사했을 수 있다」를 먼저 적고, 지운 뒤에야 그 줄을 지운다 - 중간에 죽어도 지우는 쪽이 찾을 수 있다.
 */
class BoardPublicAttachments(
    private val repository: BoardPublicAttachmentRepository,
    private val uploads: () -> PublicUploads?,
    private val time: TimeProvider,
    private val projects: ProjectRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(BoardPublicAttachments::class.java)

    /** 이 항목 하나를 맞춘다 - 이벤트(커밋 뒤)가 부른다. 실패는 삼키고 주기 조정이 맡는다 */
    fun reconcile(target: RevisionTarget, id: Long) {
        val store = uploads() ?: return
        try {
            val want = repository.eligible(target, id).toSet()
            val have = repository.copied(target, id).toSet()
            (have - want).forEach { remove(store, PublicCopy(target, id, it)) }
            (want - have).forEach { add(store, PublicCopy(target, id, it)) }
        } catch (e: Exception) {
            log.warn("public attachment reconcile failed ({} {}): {}", target, id, e.toString())
        }
    }

    /** 글이 공개를 떠나거나 돌아올 때 - 글 자신과 그 댓글들 */
    fun reconcilePost(postId: Long) {
        if (uploads() == null) return
        reconcile(RevisionTarget.POST, postId)
        runCatching { repository.commentsWithCopies(postId) }.getOrElse { emptyList() }.forEach { reconcile(RevisionTarget.COMMENT, it) }
    }

    /** 주기 조정 - 빠진 것(지우기)을 먼저, 그 다음 모자란 것(복사). 프로젝트 모드에서는 프로젝트마다 돈다. 처리한 줄 수 */
    fun sweep(limit: Int = 200): Int {
        if (uploads() == null) return 0
        var done = 0
        if (projects == null) done += sweepOne(limit) else projects.forEachProject { done += sweepOne(limit) }
        return done
    }

    private fun sweepOne(limit: Int): Int {
        val store = uploads() ?: return 0
        var done = 0
        for (copy in repository.stale(limit)) if (guarded { remove(store, copy) }) done++
        for (copy in repository.missing(limit)) if (guarded { add(store, copy) }) done++
        return done
    }

    private fun guarded(block: () -> Unit): Boolean =
        try { block(); true } catch (e: Exception) { log.warn("public attachment sweep step failed: {}", e.toString()); false }

    private fun add(store: PublicUploads, copy: PublicCopy) {
        repository.mark(copy, time.now())
        try {
            store.publish(copy.key)
        } catch (e: Exception) {
            runCatching { repository.unmark(copy) }   // 복사가 안 됐으니 「있다」고 남기지 않는다 - 다음 조정이 다시 시도한다
            throw e
        }
    }

    private fun remove(store: PublicUploads, copy: PublicCopy) {
        if (!repository.heldElsewhere(copy)) store.unpublish(copy.key)
        repository.unmark(copy)
    }
}

/** 글 · 댓글 · 판 이벤트를 받아 바로 맞춘다 - 요청이 끝날 때는 공개 영역이 맞춰져 있다 (커밋 뒤) */
class BoardPublicAttachmentListener(private val attachments: BoardPublicAttachments) : BoardEventListener {
    override fun onEvent(event: BoardEvent) {
        when (event) {
            is PostCreated -> attachments.reconcile(RevisionTarget.POST, event.post.id)
            is PostUpdated -> attachments.reconcile(RevisionTarget.POST, event.post.id)
            is PostStatusChanged -> attachments.reconcilePost(event.post.id)
            is CommentCreated -> attachments.reconcile(RevisionTarget.COMMENT, event.comment.id)
            is CommentUpdated -> attachments.reconcile(RevisionTarget.COMMENT, event.comment.id)
            is CommentStatusChanged -> attachments.reconcile(RevisionTarget.COMMENT, event.comment.id)
            is RevisionDecided -> attachments.reconcile(event.target, event.targetId)
            else -> Unit
        }
    }
}
