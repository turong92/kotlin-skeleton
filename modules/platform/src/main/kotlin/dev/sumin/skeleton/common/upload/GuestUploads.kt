package dev.sumin.skeleton.common.upload

/**
 * 로그인하지 않은 사람이 올린 파일의 저장 키인가 - storage 가 구현하고(발급한 꼴 + MAC 이 맞는 키만 참), 비회원 첨부를 받는 쪽(예: board 의 `canGuestAttach`)이 쓴다.
 * 구현이 없으면 호출하는 쪽은 지금까지처럼 키를 검사하지 않는다.
 */
fun interface GuestUploadKeys {
    fun isGuestKey(key: String): Boolean
}

/** 여러 [UploadedKeyUsage] 구현의 합집합 - 하나라도 쓰고 있다고 하면 쓰는 것이다 */
object UploadedKeyUsages {
    /** 구현이 하나도 없으면 null (회수는 아무것도 지우지 않는다) */
    fun union(all: List<UploadedKeyUsage>): UploadedKeyUsage? =
        if (all.isEmpty()) null else object : UploadedKeyUsage {
            override fun inUse(keys: Collection<String>): Set<String> = all.flatMapTo(mutableSetOf()) { it.inUse(keys) }
            override val answers: Set<String> = all.flatMapTo(mutableSetOf()) { it.answers }
        }

    /** [holders] 중 [usages] 의 어느 구현도 대답한다고 하지 않은 모듈 이름 (정렬) - 비어 있어야 회원 업로드 정리를 켤 수 있다 */
    fun unanswered(holders: Collection<UploadKeyHolder>, usages: Collection<UploadedKeyUsage>): List<String> {
        val answered = usages.flatMapTo(mutableSetOf()) { it.answers }
        return holders.map { it.module }.filter { it !in answered }.distinct().sorted()
    }
}

/**
 * 업로드 저장 키를 자기 표에 저장하는 모듈의 표지 빈 - `board` · `review` 가 조립되면 스스로 낸다(앱이 자기 키 저장소를 두면 그 앱도 하나 낸다).
 * 회원 업로드 정리는 표지마다 같은 이름을 [UploadedKeyUsage.answers] 로 대답하는 구현이 있어야 켤 수 있다 - 대답 없는 저장소가 있으면 쓰이는 파일을 지울 수 있어서다.
 */
data class UploadKeyHolder(val module: String)

/**
 * 저장 키 중 지금 어딘가(글 · 댓글 · 고친 판 …)에 붙어 있는 것 - 앱의 저장소 모듈이 구현하고(board-jdbc), storage 의 고아 회수가
 * 붙어 있지 않은 비회원 업로드만 지우려고 묻는다. **구현은 여러 개일 수 있다** - 합집합(하나라도 쓰고 있다고 하면 쓰는 것)이라 앱은 **자기 키만** 알리면 되고
 * board-jdbc 의 구현이 앱 때문에 빠지지 않는다. 구현이 없으면 회수는 아무것도 지우지 않는다.
 */
fun interface UploadedKeyUsage {
    /** [keys] 중 사용 중인 것의 부분집합 */
    fun inUse(keys: Collection<String>): Set<String>

    /** 이 구현이 대답하는 [UploadKeyHolder.module] 이름들 - 회원 업로드 정리가 켜질 때 표지마다 짝이 있는지 본다. 비회원 회수는 보지 않는다 */
    val answers: Set<String> get() = emptySet()
}
