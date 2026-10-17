package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.time.TimeProvider

class BoardService(
    private val boards: BoardRepository,
    private val policy: BoardPolicy,
    private val properties: BoardProperties,
    private val time: TimeProvider,
    private val authorRules: BoardAuthorRules,
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
            authorRequired = authorRules.active,
            guestEnabled = properties.guest.enabled,
            guestSecret = properties.guest.secret,
            guestNameMaxLength = properties.guest.nameMaxLength,
            captchaRequired = properties.guest.enabled && properties.guest.captcha == GuestCaptcha.REQUIRED,
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

    /** 운영자가 이름 · 설명 · 설정을 고친다. 설정은 칸마다 보낸 것만 바꾼다 */
    fun update(caller: BoardCaller, code: String, request: UpdateBoard): Board {
        if (!policy.canCreateBoard(caller)) throw BoardException(BoardErrorCode.FORBIDDEN, "Only moderators edit boards")
        val board = get(code)
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) throw BoardException(BoardErrorCode.CONTENT_INVALID, "Board name must not be blank")
        val s = request.settings
        val settings = s?.let {
            BoardSettings(
                guestWrite = it.guestWrite ?: board.settings.guestWrite,
                postApproval = it.postApproval ?: board.settings.postApproval,
                commentApproval = it.commentApproval ?: board.settings.commentApproval,
                ownerModeratesComments = it.ownerModeratesComments ?: board.settings.ownerModeratesComments,
            )
        }
        val change = BoardChange(name, request.description?.trim()?.ifEmpty { null }, settings)
        return boards.update(code, change) ?: throw BoardException(BoardErrorCode.NOT_FOUND, "Board not found: $code")
    }

    /** 설정의 seed-boards 를 없을 때만 만든다 — 만든 개수 */
    fun seed(): Int = properties.seedBoards.count { boards.create(it.code, it.name.trim(), it.description, time.now(), it.settings) }
}
