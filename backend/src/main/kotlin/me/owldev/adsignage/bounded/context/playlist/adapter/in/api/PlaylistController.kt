package me.owldev.adsignage.bounded.context.playlist.adapter.`in`.api

import me.owldev.adsignage.bounded.context.playlist.application.service.PlaylistService
import me.owldev.adsignage.bounded.context.playlist.domain.dto.DevicePlaylistResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 디바이스가 부팅 후/SSE 이벤트마다 호출하는 플레이리스트 엔드포인트.
 *
 *   GET /api/devices/{deviceId}/playlist
 *     공개(JWT 없음) — 디바이스가 토큰을 갖지 않으므로 SecurityConfig 에서
 *     이미 permitAll 로 화이트리스트되어 있다.
 */
@RestController
class PlaylistController(
    private val playlistService: PlaylistService,
) {
    @GetMapping(
        "/api/devices/{deviceId}/playlist",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getPlaylist(@PathVariable deviceId: String): ResponseEntity<DevicePlaylistResponse> =
        ResponseEntity.ok(playlistService.buildPlaylist(deviceId))
}
