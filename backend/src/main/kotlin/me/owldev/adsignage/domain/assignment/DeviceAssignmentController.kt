package me.owldev.adsignage.domain.assignment

import jakarta.validation.Valid
import me.owldev.adsignage.domain.assignment.dto.AssignmentResponse
import me.owldev.adsignage.domain.assignment.dto.CreateAssignmentRequest
import me.owldev.adsignage.domain.assignment.dto.UpdateAssignmentRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 디바이스 → 음식점 할당 라이프사이클을 위한 REST 엔드포인트.
 *
 * Sub-AC 3 범위:
 *  - `POST /api/devices/{id}/assignment` — 디바이스에 대해 (첫 번째) 활성
 *    할당을 생성. 멱등(Idempotent): 디바이스가 이미 활성 할당을 가지고
 *    있으면 서비스가 이를 비활성화하고 새 활성 행을 삽입 — PUT의 와이어
 *    동등 시맨틱과 일치.
 *  - `PUT /api/devices/{id}/assignment` — 디바이스를 다른 음식점으로
 *    리매핑. 이는 데모 시나리오 #3을 위한 SSE 기반 진입점.
 *
 * HTTP 계약:
 *  - 201 Created POST 성공 시 (응답 본문 = 새 활성 할당)
 *  - 200 OK      PUT 성공 시
 *  - 400 Bad Request 검증 실패 시 (GlobalExceptionHandler가 처리)
 *  - 404 Not Found device_id 또는 restaurant_id가 알려지지 않은 경우
 *
 * 해커톤 빌드에서 인가는 의도적으로 관대 — SecurityConfig가 이 라우트들을
 * 열어둠; 더 세밀한 검사는 JWT 로그인과 함께 추후 sub-AC에서 도착함.
 */
@RestController
@RequestMapping("/api/devices/{id}/assignment")
class DeviceAssignmentController(
    private val assignmentService: DeviceAssignmentService,
) {

    private val log = LoggerFactory.getLogger(DeviceAssignmentController::class.java)

    /**
     * 경로 id가 [id]인 디바이스에 대해 활성 할당을 생성.
     *
     * 영속화된 할당과 함께 201 Created를 반환. 디바이스가 이미 활성 할당을
     * 가지고 있으면 서비스가 update 시맨틱(이전 비활성화 + 새로 삽입)으로
     * 축약 — 응답은 여전히 *새* 활성 행을 운반하고, 해커톤 클라이언트를
     * 위해 POST 계약을 간단하게 유지하도록 상태는 201로 유지됨.
     */
    @PostMapping
    fun create(
        @PathVariable("id") deviceId: String,
        @Valid @RequestBody body: CreateAssignmentRequest,
    ): ResponseEntity<AssignmentResponse> {
        log.info("POST /api/devices/{}/assignment restaurantId={}", deviceId, body.restaurantId)
        val saved = assignmentService.createAssignment(deviceId, body.restaurantId)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(AssignmentResponse.from(saved))
    }

    /**
     * 경로 id가 [id]인 디바이스의 활성 할당을 [body]의 음식점을 가리키도록
     * 업데이트(리매핑).
     *
     * 새 활성 할당과 함께 200 OK를 반환. 이전에 활성이던 행은 서비스
     * 트랜잭션 내부에서 원자적으로 비활성화되므로 디바이스는 절대 절반만
     * 매핑된 상태로 관찰되지 않음.
     */
    @PutMapping
    fun update(
        @PathVariable("id") deviceId: String,
        @Valid @RequestBody body: UpdateAssignmentRequest,
    ): ResponseEntity<AssignmentResponse> {
        log.info("PUT /api/devices/{}/assignment restaurantId={}", deviceId, body.restaurantId)
        val saved = assignmentService.updateAssignment(deviceId, body.restaurantId)
        return ResponseEntity.ok(AssignmentResponse.from(saved))
    }
}
