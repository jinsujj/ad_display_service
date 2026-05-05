package me.owldev.adsignage.bounded.context.device.application.service

import me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceFieldUnsupportedException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.model.DeviceAssignment
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.domain.dto.UpdateDeviceResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AC 9, Sub-AC 1 — `PATCH /api/devices/{deviceId}`에 대한 부분 업데이트를
 * 실행하는 서비스.
 *
 * 필드별 업데이트의 *조합*을 소유하며, 필드별 구현은 소유하지 않음. 각
 * 개별 업데이트는 이미 적용 방법을 아는 기존 도메인 서비스에 위임됨
 * (`restaurantId`에 대한 [DeviceAssignmentService]). 이렇게 하면 PATCH
 * 엔드포인트가 횡단 오케스트레이션 로직에서 자유로워지고 필드별 흐름이
 * 자체 SSE 발행/감사 시맨틱을 유지할 수 있음.
 *
 * 원자성 범위: 전체 패치는 단일 `@Transactional` 경계 내부에서 실행됨.
 *
 * No-op 시맨틱: 컨트롤러가 이미 [UpdateDeviceRequest.isEmpty]를 통해
 * "적어도 하나의 필드 존재"를 강제함; 따라서 이 서비스는 적어도 하나의
 * 분기가 실행됨을 가정할 수 있음.
 */
@Service
class DeviceUpdateService(
    private val assignmentService: DeviceAssignmentService,
    private val deviceRepositoryPort: DeviceRepositoryPort,
) {

    private val log = LoggerFactory.getLogger(DeviceUpdateService::class.java)

    @Transactional
    fun applyPatch(deviceId: String, request: UpdateDeviceRequest): UpdateDeviceResponse {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        // 부모가 존재함을 미리 검증하여 *아직* 지원하지 않는 필드만 건드리는
        // PATCH가 422 대신 404로 실패하도록 함 — "device not found"가 더
        // 유용한 답.
        if (!deviceRepositoryPort.existsById(deviceId)) {
            throw DeviceNotFoundException(deviceId)
        }

        var currentAssignment: DeviceAssignment? =
            assignmentService.findCurrentAssignment(deviceId)

        // 1) restaurantId: 기존 리매핑 흐름에 위임하여 원자적 비활성화 후
        //    삽입 + SSE 발행 시맨틱을 상속받음.
        if (request.restaurantId != null) {
            currentAssignment = assignmentService.updateAssignment(
                deviceId = deviceId,
                newRestaurantId = request.restaurantId,
            )
            log.info(
                "applyPatch: device={} restaurantId={} (assignmentId={})",
                deviceId, request.restaurantId, currentAssignment.id,
            )
        }

        // 1.5) deviceName (별칭): devices 테이블의 device_name 직접 수정.
        //      DTO 검증으로 이미 1..255 길이 보장. trim 해서 양 끝 공백만 들어가는
        //      케이스도 거절.
        if (request.deviceName != null) {
            val trimmed = request.deviceName.trim()
            if (trimmed.isEmpty()) {
                throw DeviceFieldUnsupportedException("deviceName")
            }
            val device = deviceRepositoryPort.findById(deviceId)
                ?: throw DeviceNotFoundException(deviceId)
            device.deviceName = trimmed
            deviceRepositoryPort.save(device)
            log.info("applyPatch: device={} deviceName=\"{}\"", deviceId, trimmed)
        }

        // 2) screenName / groupName: 스키마가 아직 준비되지 않음. API
        //    계약이 "라우트는 존재하지만 컬럼은 아직 없음"을 500과 구분할 수
        //    있도록 타입화된 예외를 표면화.
        if (request.screenName != null) {
            log.info(
                "applyPatch: device={} screenName={} requested but column not yet provisioned",
                deviceId, request.screenName,
            )
            throw DeviceFieldUnsupportedException("screenName")
        }
        if (request.groupName != null) {
            log.info(
                "applyPatch: device={} groupName={} requested but column not yet provisioned",
                deviceId, request.groupName,
            )
            throw DeviceFieldUnsupportedException("groupName")
        }

        return UpdateDeviceResponse.fromAssignment(deviceId, currentAssignment)
    }
}
