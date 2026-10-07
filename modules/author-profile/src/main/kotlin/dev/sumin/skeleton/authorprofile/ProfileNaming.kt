package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountProperties.DisplayName.Uniqueness
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.account.NameProblem
import dev.sumin.skeleton.common.FieldValidationException

/**
 * 프로필 이름 · 꼬리표의 규칙 한 곳 — [AuthorProfileService] 가 쓴다 (동작은 서비스에 있던 그대로다).
 * 이름은 닉네임 규칙([DisplayNames])으로 받고, 꼬리표는 프로필마다 독립 추첨하며 같은 키의 아직 넘겨받지 않은 옛 작성자 꼬리표를 피한다.
 */
internal class ProfileNaming(
    private val names: DisplayNames,
    private val accounts: () -> AccountRepository,
    private val repository: AuthorProfileRepository,
) {
    /** 닉네임과 같은 규칙으로 받는다 — 단 오류 칸 이름은 `name` (계정의 `displayName` 이 아니다). 예약어는 누구에게나 걸린다 */
    fun accept(raw: String?): String {
        // 거친 상한 — 큰 본문을 정규화 · 유니코드 검사에 태우기 전에 거절한다 (이름 규칙의 최대는 60 글자라 정상 입력은 여기에 닿지 않는다). 소유 확인 뒤에 부르는 쪽(rename)은 남의 id 에 이 오류를 내지 않는다
        if (raw != null && raw.length > MAX_RAW_NAME) throw FieldValidationException(FIELD, NameProblem.TOO_LONG.fieldCode, NameProblem.TOO_LONG.message)
        return acceptRules(raw)
    }

    private fun acceptRules(raw: String?): String =
        try {
            names.accept(raw, required = true)!!
        } catch (e: FieldValidationException) {
            throw FieldValidationException(FIELD, e.fieldCode, e.message ?: e.fieldCode)
        }

    /**
     * 같은 비교용 키로 계정이 이미 보여 주는 꼬리표들 — 아직 프로필로 넘겨받지 않은 옛 작성자와 `이름#꼬리표` 가 겹치지 않게 피한다.
     * 이미 첫 프로필을 가진 계정은 뺀다: 그 계정의 글은 이제 프로필 id 로 나가니 계정 꼬리표가 공개되지 않는다.
     * 한계: 첫 프로필이 **같은 이름으로 남아 있는** 계정만 알아본다 (계정 저장소에는 "키로 계정 찾기"가 없다) — 첫 프로필을 다른 이름으로 바꾼 계정의 꼬리표는 계속 피한다(안전한 쪽).
     */
    fun avoidTags(key: String): Set<String> {
        val tags = accounts().displayTagsOf(key)
        if (tags.isEmpty()) return tags
        val primaries = repository.primariesByKey(key)
        if (primaries.isEmpty()) return tags
        val adopted = accounts().namesOf(primaries.map { it.accountId }).values
            .filter { row -> row.displayName?.let(names::key) == key }
            .mapNotNull { it.displayTag }
        return tags - adopted.toSet()
    }

    fun tagFitsMode(tag: String?) = when (names.uniqueness) {
        Uniqueness.NONE -> tag == null
        Uniqueness.UNIQUE -> tag == DisplayNames.NO_TAG
        Uniqueness.TAGGED -> tag != null && tag != DisplayNames.NO_TAG
    }

    /**
     * 키 [key] 에 꼬리표를 붙여 [attempt] 로 저장해 본다 (계정의 `placeName` 과 같은 모양). [attempt] 는 (키, 꼬리표) 유니크에 걸리면 false.
     * NONE 은 꼬리표 없이 한 번, UNIQUE 는 고정 꼬리표로 한 번(겹치거나 [avoid] 에 있으면 실패), TAGGED 는 무작위 꼬리표를 몇 번 시도하고([avoid] 에 있는 것은 시도하지 않는다)
     * 그래도 안 되면 키 안의 빈 꼬리표를 직접 찾는다 — 빈 것이 없으면(9999 명) 실패.
     */
    fun place(key: String, avoid: Set<String>, attempt: (String?) -> Boolean): Boolean {
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
        const val FIELD = "name"

        /** 이름 원문의 거친 길이 상한(UTF-16 코드 유닛) — 이름 규칙의 상한(60 글자)보다 한참 크다 */
        const val MAX_RAW_NAME = 1000

        /** 무작위 꼬리표를 몇 번 시도하고 나서 빈 꼬리표를 직접 찾나 (계정의 닉네임과 같은 값) */
        const val TAG_TRIES = 8
    }
}
