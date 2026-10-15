package dev.sumin.skeleton.app.sample.notes

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class SampleNativeHintsTest {
    @Test
    fun `the attachment key validator is registered for reflection - without it the native image answers 500 when a note carries an attachment`() {
        val hints = RuntimeHints()
        SampleNativeHints().registerHints(hints, javaClass.classLoader)
        assertTrue(RuntimeHintsPredicates.reflection().onType(OwnAttachmentKeyValidator::class.java).withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS).test(hints))
    }
}
