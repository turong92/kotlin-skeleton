package dev.sumin.skeleton.board

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.board")
data class BoardProperties(
    /** 운영자 역할 이름 (`ROLE_` 접두사 없이). 글 숨기기 · 고정 · 남의 글/댓글 지우기 */
    val moderatorRole: String = "MODERATOR",
    /** 댓글 깊이 상한 (최상위 = 0). 2 면 댓글 → 대댓글 → 대대댓글까지. 더 깊은 답글은 BOARD.COMMENT_TOO_DEEP */
    val maxCommentDepth: Int = 2,
    val titleMaxLength: Int = 200,
    val bodyMaxLength: Int = 20_000,
    val commentMaxLength: Int = 2_000,
    val maxAttachments: Int = 10,
    val excerptLength: Int = 140,
    /** 목록 한 페이지 상한 (platform 의 PageQuery 상한 100 과 둘 중 작은 값) */
    val maxPageSize: Int = 100,
    /** 기동할 때 없으면 만드는 게시판 (있으면 건드리지 않는다) */
    val seedBoards: List<SeedBoard> = emptyList(),
    val reaction: Reaction = Reaction(),
    val http: Http = Http(),
    val rateLimit: RateLimit = RateLimit(),
    val notification: Notification = Notification(),
) {
    init {
        require(moderatorRole.isNotBlank()) { "skeleton.board.moderator-role must not be blank" }
        require(maxCommentDepth >= 0) { "skeleton.board.max-comment-depth must be >= 0" }
        require(titleMaxLength > 0 && bodyMaxLength > 0 && commentMaxLength > 0) { "skeleton.board.*-max-length must be > 0" }
        require(titleMaxLength <= 255) { "skeleton.board.title-max-length must be <= 255 (the title column is varchar(255))" }
        require(maxAttachments >= 0) { "skeleton.board.max-attachments must be >= 0" }
        require(excerptLength > 0 && maxPageSize > 0) { "skeleton.board.excerpt-length and max-page-size must be > 0" }
    }

    /** 요청한 페이지 크기를 1..[maxPageSize] 로 */
    fun pageSize(requested: Int): Int = requested.coerceIn(1, maxPageSize)

    data class SeedBoard(val code: String, val name: String, val description: String? = null) {
        init {
            require(BoardCodes.isValid(code)) { "skeleton.board.seed-boards: invalid board code '$code' (${BoardCodes.PATTERN}, not '${BoardCodes.RESERVED}')" }
            require(name.isNotBlank()) { "skeleton.board.seed-boards: name of '$code' must not be blank" }
        }
    }

    data class Reaction(
        /** 허용하는 반응 종류 코드. 늘리려면 여기에 적기만 하면 된다 (스키마 · 코드 변경 없음) */
        val types: List<String> = listOf("LIKE", "DISLIKE"),
        val mode: ReactionMode = ReactionMode.SINGLE,
    ) {
        init {
            require(types.isNotEmpty()) { "skeleton.board.reaction.types must not be empty" }
            require(types.all { REACTION_CODE.matches(it) }) { "skeleton.board.reaction.types: codes must match ${REACTION_CODE.pattern}: $types" }
            require(types.distinct().size == types.size) { "skeleton.board.reaction.types must not repeat: $types" }
        }
    }

    data class Http(
        /** false 면 컨트롤러를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
        val enabled: Boolean = true,
        val basePath: String = "/api/v1/boards",
        /** true 면 GET 은 로그인 없이 (익명 읽기). 쓰기는 항상 로그인 */
        val allowAnonymousRead: Boolean = false,
    )

    data class RateLimit(
        /** 켜면 글 · 댓글 · 반응 쓰기에 계정별 한도를 건다 (platform 의 RateLimitStore — redis-rate-limit 이 있으면 그 저장소) */
        val enabled: Boolean = false,
        val capacity: Int = 30,
        val window: Duration = Duration.ofMinutes(1),
    )

    data class Notification(
        /** false 면 notification 모듈이 있어도 댓글 알림을 보내지 않는다 */
        val enabled: Boolean = true,
        val topic: String = "board",
    )

    companion object {
        val REACTION_CODE = Regex("^[A-Z][A-Z0-9_]{1,31}$")
    }
}

/** 게시판 코드 규칙 — 경로에 그대로 들어가므로 소문자 · 숫자 · 하이픈. `config` 는 설정 보고 경로라 예약. */
object BoardCodes {
    const val PATTERN = "^[a-z0-9][a-z0-9-]{1,39}$"
    const val RESERVED = "config"
    private val regex = Regex(PATTERN)

    fun isValid(code: String): Boolean = regex.matches(code) && code != RESERVED
}
