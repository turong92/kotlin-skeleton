package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.authorprofile.AuthorProfile
import java.time.Instant

/**
 * 내 작성자 프로필 — **이 네 칸이 전부다**. 계정 id · 첫 프로필 표시(`primaryOf`) · 상태 · 넘겨받은 시각은 응답에 없다 (계정과의 연결을 읽을 수 있는 칸을 만들지 않는다).
 * [name] 은 이름 없는 첫 프로필(닉네임이 없던 계정의 옛 글을 넘겨받은 것)이면 null, [tag] 는 꼬리표 방식이 꺼져 있거나(NONE) 고정 꼬리표(UNIQUE)면 null.
 */
data class AuthorProfileResponse(val id: String, val name: String?, val tag: String?, val createdAt: Instant)

data class AuthorProfileConfigResponse(
    /** 계정당 살아 있는 프로필 수 상한 (첫 프로필 포함) */
    val maxPerAccount: Int,
    /** 이름 길이 상한(코드 포인트) — 닉네임과 같다 */
    val nameMaxLength: Int,
)

/** `POST` · `PATCH` 본문. 비었거나 규칙에 어긋나면 서비스가 `400`(필드 `name`)으로 거절한다 — 모르는 칸은 무시된다 */
data class AuthorProfileNameRequest(val name: String? = null)

fun AuthorProfile.toResponse() = AuthorProfileResponse(id, name, visibleTag, createdAt)
