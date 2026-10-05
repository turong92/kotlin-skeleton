package dev.sumin.skeleton.board.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardErasureRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** 계정이 지워질 때 board 의 글 · 댓글 · 반응에서 그 계정을 익명화한다 (작성자는 "삭제된 사용자"). 행은 남는다 — 대화 맥락과 카운터를 지키려고 */
class BoardAccountErasureListener(private val repository: BoardErasureRepository) : AccountErasureListener {
    override val name: String = "board"

    override fun erase(request: ErasureRequest) {
        repository.anonymizeAuthor(request.accountId, request.tombstone)
    }
}

/**
 * [BoardErasureRepository] 가 있으면 고리를 등록한다. 고리 인터페이스는 platform 의 공용 계약이라 board 는 `account` 모듈을 모른다 —
 * 앱에 `account` 가 없으면 이 빈은 아무도 부르지 않을 뿐이다.
 */
@AutoConfiguration(after = [BoardAutoConfiguration::class], afterName = ["dev.sumin.skeleton.board.jdbc.BoardJdbcAutoConfiguration"])
@ConditionalOnBean(BoardErasureRepository::class)
class BoardErasureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["boardAccountErasureListener"])
    fun boardAccountErasureListener(repository: BoardErasureRepository): AccountErasureListener = BoardAccountErasureListener(repository)
}
