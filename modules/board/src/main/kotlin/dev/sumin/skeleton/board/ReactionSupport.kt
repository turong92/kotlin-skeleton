package dev.sumin.skeleton.board

/** 반응 종류 검증과 개수 · 내 반응을 쪽마다 쿼리 두 번(개수 · 내 것)으로 모으는 도우미. */
class ReactionSupport(private val reactions: ReactionRepository, private val properties: BoardProperties) {
    /** 설정된 종류가 아니면 BOARD.REACTION_TYPE_INVALID (코드는 대소문자를 구별한다) */
    fun requireType(type: String?): String =
        type?.takeIf { it in properties.reaction.types }
            ?: throw BoardException(BoardErrorCode.REACTION_TYPE_INVALID, "Reaction type must be one of ${properties.reaction.types}: $type")

    /** 대상 id → 반응 상태. 개수는 설정된 종류 전부(0 포함), 설정에서 빠진 옛 종류는 내보내지 않는다 */
    fun states(target: ReactionTarget, ids: Collection<Long>, caller: BoardCaller?): Map<Long, ReactionState> {
        if (ids.isEmpty()) return emptyMap()
        val counts = reactions.counts(target, ids)
        val mine = if (caller == null) emptyMap() else reactions.mine(target, ids, caller.accountId)
        return ids.associateWith { id ->
            ReactionState(
                counts = properties.reaction.types.associateWith { counts[id]?.get(it) ?: 0L },
                myReactions = mine[id].orEmpty().filter { it in properties.reaction.types }.toSet(),
            )
        }
    }

    fun state(target: ReactionTarget, id: Long, caller: BoardCaller?): ReactionState = states(target, listOf(id), caller).getValue(id)
}
