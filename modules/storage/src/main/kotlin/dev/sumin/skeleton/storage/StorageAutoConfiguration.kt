package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectScoped
import dev.sumin.skeleton.common.upload.GuestUploadKeys
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(StorageProperties::class)
class StorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun storageFileValidator(properties: StorageProperties): StorageFileValidator =
        StorageFileValidator(properties.validation)

    @Bean
    @ConditionalOnMissingBean
    fun storageKeyOwnership(properties: StorageProperties, projects: ObjectProvider<ProjectRegistry>): StorageKeyOwnership =
        StorageKeySchemes.create(properties, projects.getIfAvailable())

    /** 비회원 업로드 키 확인 - 앱이 `StorageKeyOwnership` 을 바꿔도 이 빈은 남는다. 꺼져 있으면 참이 되는 키가 없다 */
    @Bean
    @ConditionalOnMissingBean
    fun guestUploadKeys(properties: StorageProperties, projects: ObjectProvider<ProjectRegistry>): GuestUploadKeys =
        StorageGuestKeys(StorageKeySchemes.create(properties, projects.getIfAvailable()))
}

object StorageKeySchemes {
    /**
     * 서버가 정하는 키의 발급 · 소유 확인. 프로젝트 모드([registry] 가 있을 때)는 프로젝트마다 `storage.web.key-*` (공통 위에 덮어)를 읽는다 —
     * 시작 때 서비스 중인 프로젝트마다 바인딩해 보고(OPAQUE 인데 비밀이 짧으면 기동 실패, 메시지에 프로젝트 id), OPAQUE 비밀이 프로젝트끼리 같으면 기동 실패다(A 의 비밀로 B 의 키를 만들 수 있다).
     */
    fun create(properties: StorageProperties, registry: ProjectRegistry?): StorageKeyScheme {
        registry ?: return StorageKeyScheme(properties.web)
        val webs = ProjectScoped { project ->
            try {
                registry.bind(project, "skeleton.storage", StorageProperties::class.java).web
            } catch (ex: RuntimeException) {
                throw IllegalStateException("Storage of project '${project.id}' cannot start: ${generateSequence<Throwable>(ex) { it.cause }.last().message}", ex)
            }
        }
        val opaqueSecrets = registry.active.mapNotNull { project -> webs.of(project).takeIf { it.keyOwner == StorageKeyOwner.OPAQUE }?.let { project.id to it.keySecret } }
        opaqueSecrets.groupBy({ it.second }, { it.first }).values.firstOrNull { it.size > 1 }?.let { same ->
            error("Projects ${same.joinToString { "'$it'" }} use the same skeleton.storage.web.key-secret — every project needs its own (a key made with one project's secret would be owned in the other)")
        }
        val guestSecrets = registry.active.mapNotNull { project -> webs.of(project).takeIf { it.guest.enabled }?.let { project.id to it.guest.secret } }
        guestSecrets.groupBy({ it.second }, { it.first }).values.firstOrNull { it.size > 1 }?.let { same ->
            error("Projects ${same.joinToString { "'$it'" }} use the same skeleton.storage.web.guest.secret — every project needs its own (a guest key made with one project's secret would be accepted in the other)")
        }
        return StorageKeyScheme { webs.get() }
    }
}
