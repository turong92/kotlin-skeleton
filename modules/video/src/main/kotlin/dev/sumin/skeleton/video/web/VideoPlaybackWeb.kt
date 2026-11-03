package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.video.VideoLibrary
import dev.sumin.skeleton.video.VideoNotFoundException
import dev.sumin.skeleton.video.VideoPlaylists
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val HLS_PLAYLIST = MediaType("application", "vnd.apple.mpegurl", Charsets.UTF_8)

/**
 * 비공개 영상의 재생 목록 — **로그인 없이** 열린다(출입증이 경로에 있다: 사파리 기본 재생은 헤더를 못 붙인다). 출입증이 위조 · 만료 · 남의 것이거나 그 영상이 가진 목록이 아니면 모두 404.
 * 응답은 캐시하지 않는다(안의 조각 주소가 출입증 수명으로 서명된다). 조각은 저장소에서 바로 받는다
 */
@RestController
@RequestMapping("/api/v1/videos/play")
@Tag(name = "Videos")
class VideoPlaylistController(private val playlists: VideoPlaylists) {
    @Operation(summary = "A private video's playlist (token in the path); segment lines are rewritten to signed storage addresses that live as long as the token")
    @GetMapping("/{token}/{folder}/{file}")
    fun playlist(@PathVariable token: String, @PathVariable folder: String, @PathVariable file: String): ResponseEntity<String> =
        ResponseEntity.ok().contentType(HLS_PLAYLIST).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(playlists.playlist(token, "$folder/$file"))
}

/** 운영자 삭제 — [VideoOperatorPolicy] 가 허락한 사람만(기본은 모두 거절). 소유와 상관없이 지우고 되돌릴 수 없다 */
@RestController
@RequestMapping("/api/v1/videos/admin")
@Tag(name = "Videos (operator)")
class VideoAdminController(private val library: VideoLibrary, private val policy: VideoOperatorPolicy) {
    @Operation(summary = "Delete any video (reported content, takedown). reason is a short identifier for the log. 404 when there is nothing to delete")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String, @RequestParam(required = false, defaultValue = "OPERATOR") reason: String, authentication: Authentication?): ResponseEntity<Void> {
        VideoCallers.operator(authentication, policy)
        if (!library.delete(id, reason)) throw VideoNotFoundException()
        return Response.noContent()
    }
}
