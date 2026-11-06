package dev.sumin.skeleton.account.web

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `avatarKey` 의 세 상태: 필드 없음 = 그대로(null), `null` = 지움(빈 Optional), 문자열 = 설정 */
class UpdateProfileRequestJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `missing, null and a string are three different states`() {
        assertNull(mapper.readValue<UpdateProfileRequest>("""{"locale":"ko-KR"}""").avatarKey)
        assertEquals(Optional.empty(), mapper.readValue<UpdateProfileRequest>("""{"avatarKey":null}""").avatarKey)
        assertEquals(Optional.of("uploads/o/a.png"), mapper.readValue<UpdateProfileRequest>("""{"avatarKey":"uploads/o/a.png"}""").avatarKey)
    }
}
