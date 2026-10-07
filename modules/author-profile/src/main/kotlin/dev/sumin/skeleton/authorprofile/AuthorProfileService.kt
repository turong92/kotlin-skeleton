package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountProperties.DisplayName.Uniqueness
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.author.AuthorAdoptionListener
import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.LoggerFactory

/**
 * 프로필 수명 — 만들기 · 이름 바꾸기 · 지우기 · 첫 프로필. 이름 규칙 · 예약어 · 겹침 방식 · 이름 바꾸기 한도 숫자는 `skeleton.account.display-name.*` 의 것이고
 * ([DisplayNames]), 개수 상한 · 만들기 한도는 `skeleton.author-profile` 의 것이다. 한도는 모두 **계정** 기준이다 (프로필을 바꿔 한도를 피하지 못하게).
 *
 * 만들기 한도는 **표에서** 센다([AuthorProfileRepository.countCreatedSince]) — 재시작에도 같고 실패한 시도는 세지 않는다. 이름 바꾸기 한도만 `AccountRateLimits` 를 쓴다:
 * 계정의 닉네임 변경과 **따로** 세는 프로필 전용 카운터이고(숫자만 같다), 인스턴스가 여럿이면 내구성 있는 `RateLimitStore` 가 필요하다.
 *
 * 남의 · 없는 · 지워진 프로필 id 는 모두 같은 [AuthorProfileErrorCode.NOT_FOUND] 다.
 * 계정 id 와 프로필 id 의 쌍은 로그에 남기지 않는다.
 */
class AuthorProfileService(
    private val repository: AuthorProfileRepository,
    private val accounts: () -> AccountRepository,
    private val names: DisplayNames,
    private val limits: AccountRateLimits,
    private val properties: AuthorProfileProperties,
    private val time: TimeProvider,
    /** 첫 프로필을 보장할 때 그 계정의 옛 행을 바로 넘겨받게 한다 (실행기를 끈 앱 · 실행 사이의 틈) */
    private val adoptionListeners: () -> List<AuthorAdoptionListener> = { emptyList() },
) {
    /** 계정의 살아 있는 프로필 (만든 순) — 넘겨받기 보장은 하지 않는다. 목록을 부르는 쪽(GET)이 먼저 [ensureFirstProfile] 을 부른다 */
    fun list(accountId: String): List<AuthorProfile> = repository.findActive(accountId)

    /**
     * 새 프로필. 순서: 이름 규칙(어긋나면 `400` 필드 `name`, 아무것도 세지 않는다) → 첫 프로필 줄 보장([ensureFirstProfile]: 상한 검사가 잠글 줄이 늘 먼저 있게)
     * → 개수 상한(`409 LIMIT_REACHED`) → 만들기 한도(`429`, 표 기준) → 꼬리표 추첨 · 상한과 함께 원자적 저장([AuthorProfileRepository.insertWithinCap], `409 NAME_TAKEN`).
     * 꼬리표는 이 프로필 하나만의 독립 추첨이다 — 계정 · 형제 프로필에서 파생하지 않는다. 같은 키의 **아직 넘겨받지 않은** 옛 작성자 꼬리표도 피한다.
     * 개수 상한에는 첫 프로필(지우지 않았다면)도 들어간다.
     */
    fun create(accountId: String, rawName: String?): AuthorProfile {
        val name = acceptName(rawName)
        ensureFirstProfile(accountId)
        val max = properties.maxPerAccount
        if (repository.countActive(accountId) >= max) throw AuthorProfileException(AuthorProfileErrorCode.LIMIT_REACHED)
        val now = time.now()
        if (properties.createLimit > 0 && repository.countCreatedSince(accountId, now.minus(properties.createWindow)) >= properties.createLimit) {
            throw RateLimitedException(properties.createWindow.seconds.coerceAtLeast(1), AuthorProfileErrorCode.RATE_LIMITED)
        }
        val key = names.key(name)
        var outcome = CapInsert.NAME_TAKEN
        var stored: AuthorProfile? = null
        place(key, avoid = avoidTags(key)) { tag ->
            val candidate = AuthorProfile(AuthorProfileIds.newId(), accountId, null, name, key, tag, AuthorProfileStatus.ACTIVE, null, now, now, null)
            outcome = repository.insertWithinCap(candidate, max)
            if (outcome == CapInsert.INSERTED) stored = candidate
            outcome != CapInsert.NAME_TAKEN
        }
        return when (outcome) {
            CapInsert.INSERTED -> stored!!
            CapInsert.LIMIT_REACHED -> throw AuthorProfileException(AuthorProfileErrorCode.LIMIT_REACHED)
            CapInsert.NAME_TAKEN -> throw AuthorProfileException(AuthorProfileErrorCode.NAME_TAKEN)
        }
    }

    /**
     * 이름 바꾸기 — 과거 글에 바로 반영된다(읽을 때 조회). 키가 같으면(대소문자 · 전각만 바뀜) 꼬리표를 **지키고**, 다르면 새로 추첨한다.
     * 같은 값 · 규칙에 걸린 요청은 한도를 쓰지 않는다. 한도는 계정의 `display-name.change-limit`/`change-window` 숫자를 모든 프로필 합산으로 쓴다.
     */
    fun rename(accountId: String, id: String, rawName: String?): AuthorProfile {
        val current = ownLiving(accountId, id)
        val name = acceptName(rawName)
        if (name == current.name) return current
        val change = names.config
        if (change.changeLimit > 0) spend("rename", accountId, change.changeLimit, change.changeWindow)
        val key = names.key(name)
        val now = time.now()
        val kept = key == current.nameKey && tagFitsMode(current.tag)
        val result = if (kept) {
            repository.rename(id, accountId, name, key, current.tag, now)
        } else {
            var outcome = RenameResult.TAKEN
            place(key, avoid = avoidTags(key)) { tag ->
                outcome = repository.rename(id, accountId, name, key, tag, now)
                outcome != RenameResult.TAKEN
            }
            outcome
        }
        return when (result) {
            RenameResult.DONE -> repository.find(id) ?: throw notFound()
            RenameResult.TAKEN -> throw AuthorProfileException(AuthorProfileErrorCode.NAME_TAKEN)
            RenameResult.NOT_FOUND -> throw notFound()
        }
    }

    /** 즉시 지운다 — 되돌릴 수 없다. 글 · 댓글은 그대로 남고 디렉터리가 `deleted` 카드를 돌려줄 뿐이다. 다른 프로필로 옮기지 않는다 */
    fun delete(accountId: String, id: String) {
        if (!repository.markDeleted(id, accountId, time.now())) throw notFound()
    }

    /**
     * 계정의 첫 프로필을 보장하고 돌려준다 (이미 있으면 — 지워졌어도 — 그것) **그리고 그 계정의 옛 행을 넘겨받는다**: 아직 `adopted_at` 이 비었으면
     * 모든 `AuthorAdoptionListener.adopt` 를 불러 본다 (실행기를 끈 앱의 옛 글이 목록 조회 때 넘어간다 — 스펙 §7.4-3). 구현이 던지면 삼키고(종류만 로그) 다음 기회에 한다.
     * 실행기는 이것 대신 [ensureFirstProfileRow] 를 쓴다(옮긴 행 수를 직접 세려고).
     */
    fun ensureFirstProfile(accountId: String): AuthorProfile {
        val first = ensureFirstProfileRow(accountId)
        if (first.adoptedAt != null) return first
        var moved = false
        for (listener in adoptionListeners()) {
            try {
                if (listener.adopt(accountId, first.id) > 0) moved = true
            } catch (e: Exception) {
                log.warn("author profile: adoption listener '{}' failed for one account ({}); retried on the next call", listener.name, e.javaClass.simpleName)
            }
        }
        if (moved) markAdopted(accountId)
        return repository.findPrimary(accountId) ?: first
    }

    /**
     * 첫 프로필 **줄**만 보장한다 (옛 행은 옮기지 않는다). 만들 때 계정 이름 · 꼬리표를 **한 번 복사**하고 이후 따로 간다:
     * 꼬리표가 겹치거나 현재 방식에 맞지 않으면 새로 추첨하고, 이름 자리를 못 잡으면(UNIQUE 에서 이미 쓰는 이름 · 꼬리표가 가득 참) **이름 없는 첫 프로필**로 떨어진다 —
     * 첫 프로필이 없으면 넘겨받을 곳이 없어 옛 글이 영영 `pending:` 에 머물기 때문이다. 이름 없는 계정도 이름 없는 첫 프로필이다.
     * 개수 상한 · 만들기 한도를 쓰지 않는다 (사용자가 만든 것이 아니다). 모르는 · 지워진(ERASED) 계정은 [AuthorProfileErrorCode.NOT_FOUND].
     * `primary_of` 유니크와 insert-ignore 라서 인스턴스 여럿이 동시에 불러도 하나다.
     */
    fun ensureFirstProfileRow(accountId: String): AuthorProfile {
        repository.findPrimary(accountId)?.let { return it }
        val account = accounts().findById(accountId)?.takeIf { it.status != AccountStatus.ERASED } ?: throw notFound()
        val name = account.displayName
        val key = name?.let { account.displayNameKey ?: names.key(it) }
        val now = time.now()
        fun candidate(name: String?, key: String?, tag: String?) =
            AuthorProfile(AuthorProfileIds.newId(), accountId, accountId, name, key, tag, AuthorProfileStatus.ACTIVE, null, now, now, null)

        if (name == null) {
            repository.insert(candidate(null, null, null))
        } else {
            // 계정의 꼬리표를 그대로 복사해 본다 — 현재 방식에 맞고 프로필 표 안에서 겹치지 않을 때만
            val copied = tagFitsMode(account.displayTag) && repository.insert(candidate(name, key, account.displayTag))
            // 겹쳤거나 방식에 맞지 않았다 — 그 사이 다른 인스턴스가 첫 프로필을 만들었는지 먼저 보고, 아니면 새로 추첨한다
            if (!copied && repository.findPrimary(accountId) == null) {
                val placed = place(key!!, avoid = avoidTags(key)) { repository.insert(candidate(name, key, it)) }
                if (!placed && repository.findPrimary(accountId) == null) repository.insert(candidate(null, null, null))
            }
        }
        return repository.findPrimary(accountId) ?: throw notFound()
    }

    /** 첫 프로필이 옛 글을 넘겨받았다고 표시한다 (`adopted_at`, 이미 있으면 그대로) */
    fun markAdopted(accountId: String) {
        repository.markAdopted(accountId, time.now())
    }

    // ---- 내부 ----

    private fun ownLiving(accountId: String, id: String): AuthorProfile =
        repository.find(id)?.takeIf { it.accountId == accountId && it.status == AuthorProfileStatus.ACTIVE } ?: throw notFound()

    private fun notFound() = AuthorProfileException(AuthorProfileErrorCode.NOT_FOUND)

    /** 닉네임과 같은 규칙으로 받는다 — 단 오류 칸 이름은 `name` (계정의 `displayName` 이 아니다). 예약어는 누구에게나 걸린다 */
    private fun acceptName(raw: String?): String =
        try {
            names.accept(raw, required = true)!!
        } catch (e: FieldValidationException) {
            throw FieldValidationException(FIELD, e.fieldCode, e.message ?: e.fieldCode)
        }

    private fun spend(scope: String, accountId: String, capacity: Int, window: java.time.Duration) {
        val allowance = limits.acquire("author-profile:$scope", accountId, capacity, window)
        if (!allowance.allowed) throw RateLimitedException(allowance.retryAfterSeconds, AuthorProfileErrorCode.RATE_LIMITED)
    }

    /**
     * 같은 비교용 키로 계정이 이미 보여 주는 꼬리표들 — 아직 프로필로 넘겨받지 않은 옛 작성자와 `이름#꼬리표` 가 겹치지 않게 피한다.
     * 이미 첫 프로필을 가진 계정은 뺀다: 그 계정의 글은 이제 프로필 id 로 나가니 계정 꼬리표가 공개되지 않는다.
     * 한계: 첫 프로필이 **같은 이름으로 남아 있는** 계정만 알아본다 (계정 저장소에는 "키로 계정 찾기"가 없다) — 첫 프로필을 다른 이름으로 바꾼 계정의 꼬리표는 계속 피한다(안전한 쪽).
     */
    private fun avoidTags(key: String): Set<String> {
        val tags = accounts().displayTagsOf(key)
        if (tags.isEmpty()) return tags
        val primaries = repository.primariesByKey(key)
        if (primaries.isEmpty()) return tags
        val adopted = accounts().namesOf(primaries.map { it.accountId }).values
            .filter { row -> row.displayName?.let(names::key) == key }
            .mapNotNull { it.displayTag }
        return tags - adopted.toSet()
    }

    private fun tagFitsMode(tag: String?) = when (names.uniqueness) {
        Uniqueness.NONE -> tag == null
        Uniqueness.UNIQUE -> tag == DisplayNames.NO_TAG
        Uniqueness.TAGGED -> tag != null && tag != DisplayNames.NO_TAG
    }

    /**
     * 키 [key] 에 꼬리표를 붙여 [attempt] 로 저장해 본다 (계정의 `placeName` 과 같은 모양). [attempt] 는 (키, 꼬리표) 유니크에 걸리면 false.
     * NONE 은 꼬리표 없이 한 번, UNIQUE 는 고정 꼬리표로 한 번(겹치거나 [avoid] 에 있으면 실패), TAGGED 는 무작위 꼬리표를 몇 번 시도하고([avoid] 에 있는 것은 시도하지 않는다)
     * 그래도 안 되면 키 안의 빈 꼬리표를 직접 찾는다 — 빈 것이 없으면(9999 명) 실패.
     */
    private fun place(key: String, avoid: Set<String>, attempt: (String?) -> Boolean): Boolean {
        val tagged = names.uniqueness == Uniqueness.TAGGED
        repeat(if (tagged) TAG_TRIES else 1) {
            val tag = names.tagCandidate()
            if (tag != null && tag in avoid) {
                if (!tagged) return false
            } else if (attempt(tag)) {
                return true
            } else if (!tagged) {
                return false
            }
        }
        if (!tagged) return false
        val tag = names.freeTag(avoid + repository.tagsOf(key)) ?: return false
        return attempt(tag)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AuthorProfileService::class.java)
        const val FIELD = "name"

        /** 무작위 꼬리표를 몇 번 시도하고 나서 빈 꼬리표를 직접 찾나 (계정의 닉네임과 같은 값) */
        const val TAG_TRIES = 8
    }
}
