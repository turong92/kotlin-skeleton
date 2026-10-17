package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.PublicUploads
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * 공개 영역(`PublicUploads`)을 board 상태에 맞춘다 - 공개 조건(글 · 댓글 PUBLISHED)에 드는 첨부는 복사해 두고, 조건에서 빠진 것은 지운다.
 * 모든 단계는 멱등이다. **지우는 쪽이 먼저**다(공개 영역에 남으면 안 되는 쪽이 우선).
 *
 * 세 겹으로 맞춘다:
 * 1. 이벤트([reconcile]) - 커밋 뒤 곧바로. 실패하면 항목을 큐에 적는다(쓰기가 같은 트랜잭션에서 이미 적어 둔 줄이 남는다).
 * 2. 매 [TICK] [processQueue] - 큐에 적힌 항목만 다시 맞춘다 (바뀐 것만 본다 - 전체를 훑지 않는다). 계속 실패하면 시도 횟수를 세어 간격을 늘리고(지수, 최대 1시간)
 *    [alertAfterAttempts] 회부터 [BoardPublicSyncAlerts] 로 알린다. 객체 삭제(공개 쪽 보장)는 이와 상관없이 먼저 끝난다.
 * 3. 드문 [fullPass] (기본 하루 한 번, 한 인스턴스만) - 공개 조건에서 빠진 줄 · 모자란 복사 · **줄이 가리키지 않는 공개 객체**를 맞춘다.
 *    복사 · 지우기가 겹치거나 시간 초과로 줄과 객체가 어긋난 것을 잡는 그물이다.
 * 복사 전에 「복사했을 수 있다」를 먼저 적고, 복사 뒤 공개 조건을 **다시 읽어** 그 사이 빠졌으면 곧바로 지운다.
 * CDN 캐시 비우기(purge)는 요청 스레드에서 하지 않는다 - 객체를 지운 뒤 비울 공개 키만 큐에 적고, [async] 로 곧바로 한 번 + 매 [TICK] 돌린다.
 */
class BoardPublicAttachments(
    private val repository: BoardPublicAttachmentRepository,
    /** 지금 프로젝트에서 켜진 공개 영역 - 꺼져 있으면 null (부를 때마다 묻는다) */
    private val uploads: () -> PublicUploads?,
    private val time: TimeProvider,
    private val projects: ProjectRegistry? = null,
    private val fullPassInterval: Duration = Duration.ofHours(24),
    private val alerts: BoardPublicSyncAlerts = BoardPublicSyncAlerts.NONE,
    private val alertAfterAttempts: Int = 10,
    /** 비동기 실행 - 기본은 프로젝트 문맥을 들고 가는 가상 스레드. 시험은 곧바로 돌리는 것을 준다 */
    private val async: (Runnable) -> Unit = { Thread.ofVirtual().name("board-public-purge").start(ProjectContext.carry(it)) },
) {
    private val log = LoggerFactory.getLogger(BoardPublicAttachments::class.java)
    private val purging = AtomicBoolean(false)

    /**
     * 이 항목 하나를 맞춘다 - 커밋 뒤 이벤트가 부른다. 맞추면 큐의 줄을 지운다(저장소가 쓰기와 **같은 트랜잭션에서** 적어 둔 줄 - 아웃박스).
     * 실패하거나 리스너가 돌기 전에 프로세스가 죽으면 줄이 남아 [processQueue] 가 맞춘다.
     */
    fun reconcile(target: RevisionTarget, id: Long) {
        if (uploads() == null) return
        val version = runCatching { repository.queueVersion(target, id) }.getOrNull()   // 시작할 때 읽는다 - 그 뒤 쓰기가 줄을 다시 적으면 번호가 올라 줄이 남는다
        if (tryReconcile(target, id)) version?.let { runCatching { repository.dequeue(target, id, it) } } else failed(target, id)
        kickPurge()
    }

    /** 글이 공개를 떠나거나 돌아올 때 - 글 자신과 그 댓글들 */
    fun reconcilePost(postId: Long) {
        if (uploads() == null) return
        reconcile(RevisionTarget.POST, postId)
        commentsOf(postId)?.forEach { reconcile(RevisionTarget.COMMENT, it) }
    }

    /**
     * 글이 공개를 떠나거나 **돌아올 때** 맞출 댓글 - 복사가 있는 것(내려야 한다)과 첨부가 있는 것(올려야 한다) 모두.
     * 복사가 있는 것만 보면 숨겼다 다시 공개한 글의 댓글 사진은 하루 동안 복사되지 않는다. 알 수 없으면 null.
     */
    private fun commentsOf(postId: Long): List<Long>? =
        try { (repository.commentsWithCopies(postId) + repository.commentsWithAttachments(postId)).distinct() } catch (e: Exception) { log.warn("public attachment: comments of post {} unknown: {}", postId, e.toString()); null }

    /** 글 한 줄을 맞춘다 - 글이 공개를 떠나거나 돌아오면 그 댓글들의 공개 여부도 바뀐다. 모두 맞췄을 때만 참 */
    private fun tryReconcilePost(postId: Long, repair: Boolean): Boolean {
        val post = tryReconcile(RevisionTarget.POST, postId, repair)
        val comments = commentsOf(postId) ?: return false
        return comments.map { tryReconcile(RevisionTarget.COMMENT, it, repair) }.all { it } && post
    }

    /** 큐에 적힌 항목을 다시 맞추고 비울 키를 비운다 (매 [TICK]). 맞추면 줄을 지운다 - 처리한 수 */
    fun processQueue(limit: Int = 200): Int {
        if (uploads() == null) return 0
        var done = 0
        eachProject {
            if (uploads() == null) return@eachProject
            for ((target, id, version) in repository.claimQueued(limit, time.now(), LEASE)) {
                val ok = if (target == RevisionTarget.POST) tryReconcilePost(id, repair = true) else tryReconcile(target, id, repair = true)
                if (ok) { repository.dequeue(target, id, version); done++ } else failed(target, id)
            }
            done += processPurgesHere(limit)
        }
        return done
    }

    /** 비울 공개 키를 비운다 (CDN 캐시). 실패하면 시도 횟수를 세어 간격을 늘린다 - 처리한 수 */
    fun processPurges(limit: Int = 200): Int {
        if (uploads() == null) return 0
        var done = 0
        eachProject { if (uploads() != null) done += processPurgesHere(limit) }
        return done
    }

    private fun processPurgesHere(limit: Int): Int {
        val store = uploads() ?: return 0
        var done = 0
        for (key in repository.claimPurges(limit, time.now(), LEASE)) {
            try {
                store.purgePublicKey(key)
                repository.finishPurge(key)
                done++
            } catch (e: Exception) {
                val attempts = runCatching { repository.failPurge(key, time.now()) }.getOrDefault(1)
                log.warn("public attachment: CDN purge of {} failed (attempt {}): {}", key, attempts, e.toString())
                if (attempts >= alertAfterAttempts) alerts.stuck("purge:$key", attempts, e.javaClass.simpleName)
            }
        }
        return done
    }

    /** 객체를 지운 뒤 곧바로 한 번 비운다 - 요청 스레드 밖에서. 이미 돌고 있으면 새로 시작하지 않는다 (다음 [TICK] 이 나머지를 맡는다) */
    private fun kickPurge() {
        if (!purging.compareAndSet(false, true)) return
        try {
            async(Runnable { try { processPurges() } catch (e: Exception) { log.warn("public attachment purge failed: {}", e.toString()) } finally { purging.set(false) } })
        } catch (e: Exception) {
            purging.set(false)
        }
    }

    /** 드문 전체 대조 - [fullPassInterval] 마다 한 인스턴스만 돈다(DB 의 원자적 비교-교체). 고친 줄 수 */
    fun fullPass(limit: Int = 500): Int {
        if (uploads() == null) return 0
        var fixed = 0
        eachProject {
            if (uploads() != null && repository.tryStartRun("full", time.now(), fullPassInterval)) fixed += fullPassOne(limit)
        }
        return fixed
    }

    private fun eachProject(block: () -> Unit) {
        if (projects == null) block() else projects.forEachProject { block() }
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
        for (key in listed) {
            if (key !in expected && guarded { store.deletePublicKey(key); if (store.purgeEnabled()) repository.enqueuePurge(key, time.now()) }) fixed++
        }
        kickPurge()
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

    /** 맞추기 실패 - 횟수를 세어 간격을 늘리고, 상한을 넘으면 알린다. 지우는 쪽(객체 삭제)은 이와 상관없이 이미 시도됐다 */
    private fun failed(target: RevisionTarget, id: Long) {
        val attempts = runCatching { repository.recordFailure(target, id, time.now()) }.getOrNull() ?: return
        if (attempts >= alertAfterAttempts) alerts.stuck("${target.name.lowercase()}:$id", attempts, "reconcile")
    }

    private fun guarded(block: () -> Unit): Boolean =
        try { block(); true } catch (e: Exception) { log.warn("public attachment step failed: {}", e.toString()); false }

    private fun add(store: PublicUploads, copy: PublicCopy) {
        repository.mark(copy, time.now())   // 먼저 적는다 (멱등) - 복사가 어디서 끊겨도 지우는 쪽이 찾을 수 있다
        // 시간 초과 같은 실패는 복사가 됐는지 모른다 - 줄을 지우지 않는다(지우면 객체가 줄 없이 남는다). 큐에 남아 다시 시도되고, 전체 대조가 객체 없는 줄을 다시 복사하거나 거둔다
        val published = store.publish(copy.key)
        if (!published) { repository.unmark(copy); return }   // 공개할 수 없는 형식 - 복사된 것이 없다
        // 복사하는 사이 공개 조건에서 빠졌으면(가림 · 삭제 …) 곧바로 거둔다
        if (copy.key !in repository.eligible(copy.target, copy.targetId)) remove(store, copy)
    }

    /** 객체 삭제(동기, 공개 쪽 보장) -> 비울 키를 큐에 적기 -> 줄 지우기. 중간에 끊기면 줄이 남아 다시 한다 */
    private fun remove(store: PublicUploads, copy: PublicCopy) {
        if (!repository.heldElsewhere(copy)) {
            store.unpublish(copy.key)
            if (store.purgeEnabled()) repository.enqueuePurge(store.publicKeyOf(copy.key), time.now())
        }
        repository.unmark(copy)
    }

    companion object {
        /** 큐를 훑는 간격 - 주기 작업(`BoardAutoConfiguration`)이 이 값으로 돈다 */
        val TICK: Duration = Duration.ofMinutes(1)

        /** 잡은 줄의 임대 - 잡은 인스턴스가 죽으면 이 시간 뒤에 다른 인스턴스가 이어받는다. 한 틱 안에 다 처리하지 못해도 다음 틱이 같은 줄을 중복으로 잡지 않도록 틱의 둘 배로 맞춘다 */
        val LEASE: Duration = TICK.multipliedBy(2)
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
