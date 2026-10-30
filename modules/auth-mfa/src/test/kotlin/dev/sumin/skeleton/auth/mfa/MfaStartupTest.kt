package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.crypto.CryptoAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 기동 검사 - 암호화 키(`TextEncryptor`)나 저장소(`MfaStore`)가 없으면 **이름을 적고** 기동에 실패한다. 조용히 평문으로 저장하거나 기능이 사라지지 않는다 */
class MfaStartupTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration::class.java, MfaAutoConfiguration::class.java))

    private fun messages(failure: Throwable?): String = generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `without crypto keys the start fails and names TextEncryptor and where the key goes`() {
        runner.withBean(MfaStore::class.java, { FakeMfaStore() }).run { context ->
            val text = messages(context.startupFailure)
            assertNotNull(context.startupFailure)
            assertTrue("TextEncryptor" in text && "skeleton.crypto.keys" in text, text)
        }
    }

    @Test
    fun `without a store the start fails and names MfaStore and auth-mfa-jdbc`() {
        runner.withPropertyValues("skeleton.crypto.primary-key-id=local", "skeleton.crypto.keys.local=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=").run { context ->
            val text = messages(context.startupFailure)
            assertNotNull(context.startupFailure)
            assertTrue("MfaStore" in text && "auth-mfa-jdbc" in text, text)
        }
    }
}
