package dev.sumin.skeleton.account.storage

import dev.sumin.skeleton.account.AvatarKeyOwnership
import dev.sumin.skeleton.common.upload.UploadKeyHolder
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.common.upload.UploadedKeyUsages
import dev.sumin.skeleton.storage.StorageKeyOwnership
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 사진 기능은 StorageKeyOwnership 이 있을 때만 서고, 표지(UploadKeyHolder)와 사용처(UploadedKeyUsage)는 늘 짝으로 선다 */
class AccountStorageAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AccountStorageAutoConfiguration::class.java))

    @Test
    fun `without a storage ownership bean nothing is registered`() {
        runner.run {
            assertTrue(it.getBeansOfType(AvatarKeyOwnership::class.java).isEmpty())
            assertTrue(it.getBeansOfType(UploadKeyHolder::class.java).isEmpty())
            assertTrue(it.getBeansOfType(UploadedKeyUsage::class.java).isEmpty())
        }
    }

    @Test
    fun `with storage ownership the feature, the holder and its answering usage appear together`() {
        runner.withBean(StorageKeyOwnership::class.java, { StorageKeyOwnership { account, key -> key.startsWith("uploads/$account/") } }).run {
            val owner = it.getBean(AvatarKeyOwnership::class.java)
            assertTrue(owner.owns("a1", "uploads/a1/x.png"))
            assertTrue(!owner.owns("a1", "uploads/a2/x.png"))
            val holders = it.getBeansOfType(UploadKeyHolder::class.java).values
            val usages = it.getBeansOfType(UploadedKeyUsage::class.java).values
            assertEquals(listOf("account"), holders.map { h -> h.module })
            assertEquals(emptyList(), UploadedKeyUsages.unanswered(holders, usages))
        }
    }
}
