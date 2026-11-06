package dev.sumin.skeleton.account.storage

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountAvatars
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.common.upload.UploadKeyOwnership
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.upload.UploadKeyHolder
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * 프로필 사진(아바타) - 키 소유 확인 빈([UploadKeyOwnership], platform 포트 - `storage` 의 `StorageKeyOwnership` 이 이것을 확장해 `key-owner` 방식에 맞는 구현을 낸다)이 있을 때만 켜진다.
 * 이 모듈은 `storage` 를 컴파일에 끌어오지 않는다(`GuestUploadKeys` 와 같은 방식). 빈이 없으면 이 설정 전체가 빠지고 계정은 사진 없이 그대로 돈다.
 * 소유 확인을 바꾸려면: `storage` 가 있는 앱은 `StorageKeyOwnership` 을 교체한다(storage 의 기본은 그 타입이 없을 때만 선다) - [UploadKeyOwnership] 을 따로 두면 둘이 함께 서서 유일한 빈을 못 고른다. `storage` 가 없는 앱만 [UploadKeyOwnership] 을 직접 둔다.
 * 사진 키를 계정 표(`accounts.avatar_key`)에 저장하므로 storage 의 버려진 업로드 정리에 알린다: 표지 `account` 와 같은 이름에 대답하는 사용처 - 둘이 짝이다.
 */
@AutoConfiguration(before = [AccountAutoConfiguration::class], afterName = ["dev.sumin.skeleton.storage.StorageAutoConfiguration"])
class AccountStorageAutoConfiguration {
    @Bean
    @ConditionalOnBean(UploadKeyOwnership::class)
    @ConditionalOnMissingBean(name = ["accountUploadKeyHolder"])
    fun accountUploadKeyHolder(): UploadKeyHolder = UploadKeyHolder(UPLOAD_KEY_MODULE)

    /** 타입으로 비키지 않는다 - 다른 구현과 합집합으로 쓰인다 (UploadedKeyUsages) */
    @Bean
    @ConditionalOnBean(UploadKeyOwnership::class)
    @ConditionalOnMissingBean(name = ["accountUploadedKeyUsage"])
    fun accountUploadedKeyUsage(accounts: ObjectProvider<AccountRepository>): UploadedKeyUsage = object : UploadedKeyUsage {
        override val answers: Set<String> = setOf(UPLOAD_KEY_MODULE)
        override fun inUse(keys: Collection<String>): Set<String> = if (keys.isEmpty()) emptySet() else accounts.getObject().avatarKeysInUse(keys)
    }

    /** 계정이 지워질 때 사진의 공개 복사도 지운다 (원본은 비공개에 남아 버려진 업로드 정리가 거둔다). 멱등 */
    @Bean
    @ConditionalOnBean(UploadKeyOwnership::class)
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
