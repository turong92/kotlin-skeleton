package dev.sumin.skeleton.account.storage

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountAvatars
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AvatarKeyOwnership
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.upload.UploadKeyHolder
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.storage.StorageKeyOwnership
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * 프로필 사진(아바타) - `storage` 가 클래스패스에 있고 [StorageKeyOwnership] 빈이 있을 때만 켜진다 (`ReviewStorageAutoConfiguration` 과 같은 방식). 없으면 이 설정 전체가 빠지고 계정은 사진 없이 그대로 돈다.
 * 소유 확인은 `storage` 의 `key-owner` 방식에 맞는 빈이 한다. 앱이 [AvatarKeyOwnership] 빈을 두면 그것이 이긴다.
 * 사진 키를 계정 표(`accounts.avatar_key`)에 저장하므로 storage 의 버려진 업로드 정리에 알린다: 표지 `account` 와 같은 이름에 대답하는 사용처 - 둘이 짝이다.
 */
@AutoConfiguration(before = [AccountAutoConfiguration::class], afterName = ["dev.sumin.skeleton.storage.StorageAutoConfiguration"])
@ConditionalOnClass(StorageKeyOwnership::class)
class AccountStorageAutoConfiguration {
    @Bean
    @ConditionalOnBean(StorageKeyOwnership::class)
    @ConditionalOnMissingBean(AvatarKeyOwnership::class)
    fun accountAvatarKeyOwnership(ownership: StorageKeyOwnership): AvatarKeyOwnership = AvatarKeyOwnership(ownership::owns)

    @Bean
    @ConditionalOnBean(AvatarKeyOwnership::class)
    @ConditionalOnMissingBean(name = ["accountUploadKeyHolder"])
    fun accountUploadKeyHolder(): UploadKeyHolder = UploadKeyHolder(UPLOAD_KEY_MODULE)

    /** 타입으로 비키지 않는다 - 다른 구현과 합집합으로 쓰인다 (UploadedKeyUsages) */
    @Bean
    @ConditionalOnBean(AvatarKeyOwnership::class)
    @ConditionalOnMissingBean(name = ["accountUploadedKeyUsage"])
    fun accountUploadedKeyUsage(accounts: ObjectProvider<AccountRepository>): UploadedKeyUsage = object : UploadedKeyUsage {
        override val answers: Set<String> = setOf(UPLOAD_KEY_MODULE)
        override fun inUse(keys: Collection<String>): Set<String> = if (keys.isEmpty()) emptySet() else accounts.getObject().avatarKeysInUse(keys)
    }

    /** 계정이 지워질 때 사진의 공개 복사도 지운다 (원본은 비공개에 남아 버려진 업로드 정리가 거둔다). 멱등 */
    @Bean
    @ConditionalOnBean(AvatarKeyOwnership::class)
    @ConditionalOnMissingBean(name = ["accountAvatarErasureListener"])
    fun accountAvatarErasureListener(accounts: ObjectProvider<AccountRepository>, avatars: ObjectProvider<AccountAvatars>): AccountErasureListener = object : AccountErasureListener {
        override val name: String = "account-avatar"
        override fun erase(request: ErasureRequest) {
            avatars.getObject().retireOrThrow(accounts.getObject().findById(request.accountId)?.avatarKey, force = true)
        }
    }

    private companion object {
        /** [UploadKeyHolder.module] - `UploadedKeyUsage.answers` 가 같은 이름을 대답한다 */
        const val UPLOAD_KEY_MODULE = "account"
    }
}
