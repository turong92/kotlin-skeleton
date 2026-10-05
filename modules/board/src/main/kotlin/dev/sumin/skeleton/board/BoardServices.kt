package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.time.TimeProvider

class BoardService(
    private val boards: BoardRepository,
    private val policy: BoardPolicy,
    private val properties: BoardProperties,
    private val time: TimeProvider,
) {
    fun config(caller: BoardCaller?): BoardConfigView =
        BoardConfigView(
            reactionTypes = properties.reaction.types,
            reactionMode = properties.reaction.mode,
            maxCommentDepth = properties.maxCommentDepth,
            titleMaxLength = properties.titleMaxLength,
            bodyMaxLength = properties.bodyMaxLength,
            commentMaxLength = properties.commentMaxLength,
            maxPageSize = properties.maxPageSize,
            canModerate = caller != null && policy.canModerate(caller),
        )

    fun list(): List<Board> = boards.findAll()

    fun get(code: String): Board = boards.find(code) ?: throw BoardException(BoardErrorCode.NOT_FOUND, "Board not found: $code")

    fun create(caller: BoardCaller, code: String, name: String, description: String?): Board {
        if (!policy.canCreateBoard(caller)) throw BoardException(BoardErrorCode.FORBIDDEN, "Only moderators create boards")
        if (!BoardCodes.isValid(code)) throw BoardException(BoardErrorCode.CONTENT_INVALID, "Board code must match ${BoardCodes.PATTERN} and not be '${BoardCodes.RESERVED}'")
        if (name.isBlank()) throw BoardException(BoardErrorCode.CONTENT_INVALID, "Board name must not be blank")
        if (!boards.create(code, name.trim(), description?.trim()?.ifEmpty { null }, time.now())) {
            throw BoardException(BoardErrorCode.CODE_TAKEN, "Board code already taken: $code")
        }
        return get(code)
    }

    /** 설정의 seed-boards 를 없을 때만 만든다 — 만든 개수 */
    fun seed(): Int = properties.seedBoards.count { boards.create(it.code, it.name.trim(), it.description, time.now()) }
}
