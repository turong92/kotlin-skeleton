package dev.sumin.skeleton.authorprofiletest

import dev.sumin.skeleton.authorprofile.AuthorProfileRepository
import dev.sumin.skeleton.authorprofile.FakeAuthorProfileRepository
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class AuthorProfileWebTestApplication

@TestConfiguration(proxyBeanMethods = false)
class FakeAuthorProfileStore {
    @Bean fun authorProfileRepository(): AuthorProfileRepository = FakeAuthorProfileRepository()
}
