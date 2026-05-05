package me.owldev.adsignage.bounded.context.assignment.domain.exception

/**
 * [me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService] 작업이
 * devices 테이블에 없는 device_id를 참조할 때 던져짐. HTTP 404로 매핑됨.
 */
class DeviceNotFoundException(val deviceId: String) :
    RuntimeException("Device not found: $deviceId")

/**
 * [me.owldev.adsignage.bounded.context.assignment.application.service.DeviceAssignmentService] 작업이
 * restaurants 테이블에 없는 restaurant_id를 참조할 때 던져짐. HTTP 404로 매핑됨.
 */
class RestaurantNotFoundException(val restaurantId: String) :
    RuntimeException("Restaurant not found: $restaurantId")

/**
 * 호출자가 현재 할당이 없는 디바이스의 현재 할당을 요청할 때 던져짐.
 * HTTP 404로 매핑됨.
 */
class AssignmentNotFoundException(val deviceId: String) :
    RuntimeException("No active assignment for device: $deviceId")

/**
 * AC 9, Sub-AC 1 — 호출자가 와이어 계약에 명명되어 있지만 이 빌드의
 * 저장소에 의해 아직 뒷받침되지 않은 디바이스 필드(현재: `screenName`,
 * `groupName`)를 대상으로 할 때 DeviceUpdateService.applyPatch가 던짐.
 * HTTP 422 Unprocessable Entity로 매핑되어 관리자 UI가 "오타를 보냈다"(400)와
 * "서버는 필드를 이해했지만 아직 영속화할 수 없음"의 차이를 구분할 수 있음.
 *
 * 과도기적 모양은 자기 문서적으로 유지됨: 형제 sub-AC에서 V10 `devices`
 * 테이블에 해당 컬럼이 자라면 서비스는 throw를 UPDATE로 교체할 것이며 이
 * 예외는 단순히 발생하지 않게 됨.
 */
class DeviceFieldUnsupportedException(val field: String) :
    RuntimeException("Device field not yet supported: $field")
