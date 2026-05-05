package me.owldev.adsignage.domain.assignment

import me.owldev.adsignage.domain.assignment.dto.DeviceResponse
import me.owldev.adsignage.domain.assignment.dto.UpdateDeviceRequest
import me.owldev.adsignage.bounded.context.device.application.port.out.database.DeviceRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AC 9, Sub-AC 1 — `PATCH /api/devices/{deviceId}`에 대한 부분 업데이트를
 * 실행하는 서비스.
 *
 * 필드별 업데이트의 *조합*을 소유하며, 필드별 구현은 소유하지 않음. 각
 * 개별 업데이트는 이미 적용 방법을 아는 기존 도메인 서비스에 위임됨
 * (현재: `restaurantId`에 대한 [DeviceAssignmentService]만; 향후: V10
 * `devices` 테이블에 해당 컬럼이 자라면 screen/group 필드를 위한 형제
 * 서비스). 이렇게 하면 PATCH 엔드포인트가 횡단 오케스트레이션 로직에서
 * 자유로워지고 필드별 흐름이 자체 SSE 발행/감사 시맨틱을 유지할 수 있음.
 *
 * 원자성 범위: 전체 패치는 단일 `@Transactional` 경계 내부에서 실행됨.
 * 호출자가 `restaurantId`와 향후 `screenName`을 PATCH하면 둘 다 적용되거나
 * 둘 다 롤백됨 — 와이어에 절반 적용된 PATCH는 없음.
 *
 * No-op 시맨틱: 컨트롤러가 이미 [UpdateDeviceRequest.isEmpty]를 통해
 * "적어도 하나의 필드 존재"를 강제함; 따라서 이 서비스는 적어도 하나의
 * 분기가 실행됨을 가정할 수 있음.
 */
@Service
class DeviceUpdateService(
    private val assignmentService: DeviceAssignmentService,
    private val deviceLookup: DeviceLookup,
    private val deviceRepository: DeviceRepositoryPort,
) {

    private val log = LoggerFactory.getLogger(DeviceUpdateService::class.java)

    /**
     * id가 [deviceId]인 디바이스에 부분 [request]를 적용.
     *
     * 필드별 흐름:
     *  - `restaurantId` → [DeviceAssignmentService.updateAssignment]에 위임,
     *    이는 기존 활성 행을 원자적으로 비활성화하고 새 활성 행을 삽입한
     *    뒤 SSE 브리지가 소비하는 `DeviceMappingChangedEvent`를 발행.
     *  - `screenName` / `groupName` → 빌드의 이 시점에서 아직 영속화되지
     *    않음(V10 `devices` 테이블이 아직 해당 컬럼을 가지지 않음). 서비스는
     *    요청을 로깅하고 [DeviceFieldUnsupportedException]을 발생시켜
     *    컨트롤러가 필드를 조용히 드롭하는 대신 타입화된 422를 표면화할 수
     *    있게 함. 향후 sub-AC에서 컬럼을 추가하고 throw를 UPDATE로 교체할
     *    것.
     *
     * @throws DeviceNotFoundException     `devices`에서 [deviceId]에 해당하는 행이 없을 때
     * @throws RestaurantNotFoundException [request.restaurantId]가 non-null이고 알려지지 않았을 때
     * @throws DeviceFieldUnsupportedException [request.screenName] 또는 [request.groupName]이
     *         non-null일 때(과도기적; 위의 필드 수준 흐름 참조)
     */
    @Transactional
    fun applyPatch(deviceId: String, request: UpdateDeviceRequest): DeviceResponse {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }

        // 부모가 존재함을 미리 검증하여 *아직* 지원하지 않는 필드만 건드리는
        // PATCH가 422 대신 404로 실패하도록 함 — "device not found"가 더
        // 유용한 답.
        if (!deviceLookup.exists(deviceId)) {
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
            val device = deviceRepository.findById(deviceId)
                ?: throw DeviceNotFoundException(deviceId)
            device.deviceName = trimmed
            deviceRepository.save(device)
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

        return DeviceResponse.fromAssignment(deviceId, currentAssignment)
    }
}
