package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.PublicUploads
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * 공개 영역(`PublicUploads`)을 board 상태에 맞춘다 - 공개 조건(글 · 댓글 PUBLISHED)에 드는 첨부는 복사해 두고, 조건에서 빠진 것은 지운다.
 * 모든 단계는 멱등이다. **지우는 쪽이 먼저**다(공개 영역에 남으면 안 되는 쪽이 우선).
 *
 * 세 겹으로 맞춘다:
 * 1. 이벤트([reconcile]) - 커밋 뒤 곧바로. 실패하면 항목을 표에 적는다.
 * 2. 매분 [processQueue] - 표에 적힌 항목만 다시 맞춘다 (바뀐 것만 본다 - 전체를 훑지 않는다).
 * 3. 드문 [fullPass] (기본 하루 한 번, 한 인스턴스만) - 공개 조건에서 빠진 줄 · 모자란 복사 · **줄이 가리키지 않는 공개 객체**를 맞춘다.
 *    복사 · 지우기가 겹치거나 시간 초과로 줄과 객체가 어긋난 것을 잡는 그물이다.
 * 복사 전에 「복사했을 수 있다」를 먼저 적고, 복사 뒤 공개 조건을 **다시 읽어** 그 사이 빠졌으면 곧바로 지운다.
 */
class BoardPublicAttachments(
    private val repository: BoardPublicAttachmentRepository,
    private val uploads: () -> PublicUploads?,
    private val time: TimeProvider,
    private val projects: ProjectRegistry? = null,
    private val fullPassInterval: Duration = Duration.ofHours(24),
) {
    private val log = LoggerFactory.getLogger(BoardPublicAttachments::class.java)

    /** 이 항목 하나를 맞춘다 - 이벤트(커밋 뒤)가 부른다. 실패는 삼키고 표에 적어 둔다 */
    fun reconcile(target: RevisionTarget, id: Long) {
        if (uploads() == null) return
        if (!tryReconcile(target, id)) runCatching { repository.enqueue(target, id, time.now()) }
    }

    /** 글이 공개를 떠나거나 돌아올 때 - 글 자신과 그 댓글들 */
    fun reconcilePost(postId: Long) {
        if (uploads() == null) return
        reconcile(RevisionTarget.POST, postId)
        val comments = try { repository.commentsWithCopies(postId) } catch (e: Exception) { log.warn("public attachment: comments of post {} unknown: {}", postId, e.toString()); emptyList() }
        comments.forEach { reconcile(RevisionTarget.COMMENT, it) }
    }

    /** 표에 적힌 항목을 다시 맞춘다 (매분). 맞추면 줄을 지운다 - 처리한 수 */
    fun processQueue(limit: Int = 200): Int {
        if (uploads() == null) return 0
        var done = 0
        val each = { _: Unit ->
            for ((target, id) in repository.claimQueued(limit, time.now(), Duration.ofMinutes(2))) {
                if (tryReconcile(target, id, repair = true)) { repository.dequeue(target, id); done++ }
            }
        }
        if (projects == null) each(Unit) else projects.forEachProject { each(Unit) }
        return done
    }

    /** 드문 전체 대조 - [fullPassInterval] 마다 한 인스턴스만 돈다(DB 의 원자적 비교-교체). 고친 줄 수 */
    fun fullPass(limit: Int = 500): Int {
        if (uploads() == null) return 0
        var fixed = 0
        val each = { _: Unit ->
            if (repository.tryStartRun("full", time.now(), fullPassInterval)) fixed += fullPassOne(limit)
        }
        if (projects == null) each(Unit) else projects.forEachProject { each(Unit) }
        return fixed
    }

    private fun fullPassOne(limit: Int): Int {
        val store = uploads() ?: return 0
        var fixed = 0
        // 1) 줄이 있는데 공개 조건에서 빠진 것 - 지운다 (지우는 쪽 먼저)
        fixed += drain({ repository.stale(limit) }) { remove(store, it) }
        // 2) 공개 조건에 드는데 복사가 없는 것
        fixed += drain({ repository.missing(limit) }) { add(store, it) }
        // 3) 줄이 가리키지 않는 공개 객체 - 객체를 먼저 나열한 **뒤에** 줄을 읽는다: 줄은 복사보다 먼저 적히므로, 나열된 객체는 줄에 있거나 지워진 것이다
        val listed = store.listPublicKeys()
        val listedSet = listed.toHashSet()
        val expected = HashSet<String>()
        var after: PublicCopy? = null
        while (true) {
            val page = repository.copiesPage(after, 1000)
            if (page.isEmpty()) break
            page.forEach {
                val publicKey = store.publicKeyOf(it.key)
                expected += publicKey
                // 줄은 있는데 객체가 없다(복사가 끊겼다) - 공개 조건에 들면 다시 복사하고, 아니면 줄을 거둔다
                if (publicKey !in listedSet && guarded { if (it.key in repository.eligible(it.target, it.targetId)) add(store, it) else remove(store, it) }) fixed++
            }
            after = page.last()
        }
        for (key in listed) if (key !in expected && guarded { store.deletePublicKey(key) }) fixed++
        return fixed
    }

    private fun drain(next: () -> List<PublicCopy>, step: (PublicCopy) -> Unit): Int {
        var done = 0
        while (true) {
            val batch = next()
            val ok = batch.count { guarded { step(it) } }
            done += ok
            if (batch.isEmpty() || ok == 0) return done   // 한 줄도 못 고쳤으면 멈춘다 - 같은 줄을 돌지 않게
        }
    }

    /** [repair] 면 이미 복사했다고 적힌 것도 다시 복사한다 - 앞선 시도가 시간 초과로 끊겨 객체가 있는지 모를 때 */
    private fun tryReconcile(target: RevisionTarget, id: Long, repair: Boolean = false): Boolean {
        val store = uploads() ?: return true
        return try {
            val want = repository.eligible(target, id).toSet()
            val have = repository.copied(target, id).toSet()
            (have - want).forEach { remove(store, PublicCopy(target, id, it)) }
            ((want - have) + if (repair) want.intersect(have) else emptySet()).forEach { add(store, PublicCopy(target, id, it)) }
            true
        } catch (e: Exception) {
            log.warn("public attachment reconcile failed ({} {}): {}", target, id, e.toString())
            false
        }
    }

    private fun guarded(block: () -> Unit): Boolean =
        try { block(); true } catch (e: Exception) { log.warn("public attachment step failed: {}", e.toString()); false }

    private fun add(store: PublicUploads, copy: PublicCopy) {
        repository.mark(copy, time.now())   // 먼저 적는다 (멱등) - 복사가 어디서 끊겨도 지우는 쪽이 찾을 수 있다
        // 시간 초과 같은 실패는 복사가 됐는지 모른다 - 줄을 지우지 않는다(지우면 객체가 줄 없이 남는다). 표에 적혀 다시 시도되고, 전체 대조가 객체가 없는 줄을 다시 복사하거나 거둔다
        val published = store.publish(copy.key)
        if (!published) { repository.unmark(copy); return }   // 공개할 수 없는 형식 - 복사된 것이 없다
        // 복사하는 사이 공개 조건에서 빠졌으면(가림 · 삭제 …) 곧바로 거둔다
        if (copy.key !in repository.eligible(copy.target, copy.targetId)) remove(store, copy)
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
