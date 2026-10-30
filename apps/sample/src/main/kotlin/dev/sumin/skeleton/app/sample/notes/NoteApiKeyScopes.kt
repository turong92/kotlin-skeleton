package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.common.apikey.ApiKeyScopeDeclaration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 노트가 API 키로 내놓는 범위 둘 - 키는 발급 때 이 중에서 고른다. 경로 쪽 표시는 [NoteController] 의 `@ApiKeyScope` */
object NoteApiKeyScopes {
    const val READ = "notes.read"
    const val WRITE = "notes.write"
}

@Configuration(proxyBeanMethods = false)
class NoteApiKeyScopesConfiguration {
    @Bean
    fun noteApiKeyScopes(): ApiKeyScopeDeclaration = ApiKeyScopeDeclaration("notes", listOf(NoteApiKeyScopes.READ, NoteApiKeyScopes.WRITE))
}
