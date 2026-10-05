package dev.sumin.skeleton.boardtest

import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.FakeBoardRepository
import dev.sumin.skeleton.board.FakeCommentRepository
import dev.sumin.skeleton.board.FakePostRepository
import dev.sumin.skeleton.board.FakeReactionRepository
import dev.sumin.skeleton.board.FakeStore
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.ReactionRepository
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class BoardWebTestApplication

@TestConfiguration(proxyBeanMethods = false)
class FakeBoardRepositories {
    @Bean fun fakeStore() = FakeStore()
    @Bean fun boardRepository(s: FakeStore): BoardRepository = FakeBoardRepository(s)
    @Bean fun postRepository(s: FakeStore): PostRepository = FakePostRepository(s)
    @Bean fun commentRepository(s: FakeStore): CommentRepository = FakeCommentRepository(s)
    @Bean fun reactionRepository(s: FakeStore): ReactionRepository = FakeReactionRepository(s)
}
