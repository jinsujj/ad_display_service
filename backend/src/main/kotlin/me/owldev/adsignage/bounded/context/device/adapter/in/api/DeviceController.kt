package me.owldev.adsignage.bounded.context.device.adapter.`in`.api

import jakarta.validation.Valid
import me.owldev.adsignage.bounded.context.device.application.service.DeviceService
import me.owldev.adsignage.bounded.context.device.domain.dto.DeviceDetailResponse
import me.owldev.adsignage.bounded.context.device.domain.dto.DeviceListItem
import me.owldev.adsignage.bounded.context.device.domain.dto.RegisterDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.RegisterDeviceResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 디바이스 등록 + 목록 조회 + 라이프사이클 엔드포인트.
 *
 *   POST /api/devices/register
 *     안드로이드 광고판이 부팅 시 자기 device_id 를 신고. 인증 없음 — 디바이스가
 *     JWT 를 갖지 않으므로 공개 엔드포인트 (SecurityConfig 에 permitAll 추가됨).
 *     멱등 — 이미 등록된 device_id 면 last_seen_at 만 갱신.
 *
 *   POST /api/devices/{deviceId}/offline
 *     디바이스가 종료/슬립으로 들어갈 때 sendBeacon 으로 자기 자신을 즉시
 *     오프라인으로 알림. 인증 없음.
 *
 *   DELETE /api/devices/{deviceId}
 *     어드민이 디바이스 + 매핑 이력 + 큐를 일괄 정리. JWT 필요.
 *
 *   GET /api/devices/{deviceId}
 *     단일 디바이스 상세 + 매핑 이력. JWT 필요.
 *
 *   GET /api/devices
 *     광고주/운영자 어드민이 보는 디바이스 목록 — 활성 매핑·큐·현재 송출
 *     광고·온라인 여부를 한 번에 묶어 반환. JWT 필요.
 */
@RestController
class DeviceController(
    private val deviceService: DeviceService,
) {
    @PostMapping(
        "/api/devices/register",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun register(@Valid @RequestBody body: RegisterDeviceRequest): ResponseEntity<RegisterDeviceResponse> {
        val response = deviceService.register(body.deviceId!!, body.deviceName)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/api/devices/{deviceId}/offline")
    fun reportOffline(@PathVariable deviceId: String): ResponseEntity<Void> {
        deviceService.reportOffline(deviceId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/api/devices/{deviceId}")
    fun deleteDevice(@PathVariable deviceId: String): ResponseEntity<Void> {
        deviceService.delete(deviceId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping(
        "/api/devices/{deviceId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getDeviceDetail(@PathVariable deviceId: String): ResponseEntity<DeviceDetailResponse> {
        val response = deviceService.getDetail(deviceId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(response)
    }

    @GetMapping(
        "/api/devices",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun listDevices(): ResponseEntity<List<DeviceListItem>> =
        ResponseEntity.ok(deviceService.list())
}
