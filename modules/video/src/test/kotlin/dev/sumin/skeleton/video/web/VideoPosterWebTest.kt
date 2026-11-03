package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StorageWebProperties
import dev.sumin.skeleton.video.FakeFollowUps
import dev.sumin.skeleton.video.FakeStorage
import dev.sumin.skeleton.video.FakeTransactions
import dev.sumin.skeleton.video.FakeVideoStore
import dev.sumin.skeleton.video.OwnerVideoAccessPolicy
import dev.sumin.skeleton.video.PlaybackFixtures
import dev.sumin.skeleton.video.VideoKeys
import dev.sumin.skeleton.video.VideoPosters
import dev.sumin.skeleton.video.VideoProperties
import kotlin.test.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 포스터 바꿔 끼우기 HTTP 모양 — 남의 영상은 404, 이미지 모듈이 없으면 422, 로그인하지 않으면 401 */
class VideoPosterWebTest {
    private val store = FakeVideoStore().also { it.rows["vid1"] = PlaybackFixtures.video() }
    private val posters = VideoPosters(
        store, FakeStorage(), VideoKeys(VideoProperties(enabled = true), StorageWebProperties()), OwnerVideoAccessPolicy, { _, _ -> true }, { null },
        FakeFollowUps(), FakeTransactions(), TimeProvider { PlaybackFixtures.t0 },
    )
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(VideoPosterController(posters)).setControllerAdvice(GlobalExceptionHandler()).build()

    private fun caller(id: String) = TestingAuthenticationToken(id, "n/a", "ROLE_USER").apply { isAuthenticated = true }

    private fun call(account: String?, id: String = "vid1", body: String = """{"key":"uploads/acc_a/u1/file.jpg"}""") =
        mvc.perform(put("/api/v1/videos/$id/poster").apply { account?.let { principal(caller(it)) } }.contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `the poster route answers 401 anonymous, 404 for someone else's video, 400 without a key and 422 when the app has no image module`() {
        call(null).andExpect(status().isUnauthorized)
        call("acc_b").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("VIDEO.NOT_FOUND"))
        call("acc_a", body = "{}").andExpect(status().isBadRequest)
        call("acc_a").andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("VIDEO.POSTER_REJECTED"))
    }
}
