package me.owldev.adsignage.web

import jakarta.validation.ConstraintViolationException
import me.owldev.adsignage.auth.DuplicateEmailException
import me.owldev.adsignage.auth.InvalidCredentialsException
import me.owldev.adsignage.bounded.context.ad.domain.exception.AdNotFoundException
import me.owldev.adsignage.bounded.context.ad.domain.exception.InvalidScheduleException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.AssignmentNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceFieldUnsupportedException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.RestaurantNotFoundException
import me.owldev.adsignage.domain.video.streaming.UnsatisfiableRangeException
import me.owldev.adsignage.domain.video.streaming.VideoNotFoundException
import me.owldev.adsignage.domain.video.upload.EmptyVideoUploadException
import me.owldev.adsignage.domain.video.upload.InvalidVideoMimeTypeException
import me.owldev.adsignage.domain.video.upload.MissingVideoFilenameException
import me.owldev.adsignage.domain.video.upload.VideoTooLargeException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

/**
 * 예외를 HTTP 응답으로 매핑하는 중앙화된 정의.
 *
 * Sub-AC 2 케이스:
 *  - `@Valid`의 빈 검증 실패 → 필드 오류 맵과 함께 400
 *  - 회원가입 시 이메일 충돌                → 409 Conflict
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    data class ApiError(
        val timestamp: Instant = Instant.now(),
        val status: Int,
        val error: String,
        val message: String,
        val fieldErrors: Map<String, String>? = null,
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val fieldErrors = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "invalid")
        }
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = "Request body validation failed",
            fieldErrors = fieldErrors,
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Sub-AC 5: 경로/쿼리 파라미터(또는 서비스 메서드 인자)에 대한
     * jakarta-validation `@Validated` 제약은 `@RequestBody`의 `@Valid`가
     * 트리거하는 [MethodArgumentNotValidException] 대신
     * [ConstraintViolationException]을 발생시킴. 둘 다 동일한 모양의 400으로
     * 표면화되어야 호출자가 스프링이 어떤 어노테이션을 인식했는지 무관하게
     * 필드 수준 오류를 균일하게 렌더링할 수 있음.
     *
     * `propertyPath`는 마지막 세그먼트로 축소됨(예: `getDevice.deviceId` →
     * `deviceId`)므로 응답이 클라이언트가 실제로 제출한 필드 이름과 일치함.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiError> {
        val fieldErrors = ex.constraintViolations.associate { v ->
            val path = v.propertyPath.toString()
            val field = path.substringAfterLast('.', missingDelimiterValue = path)
            field to (v.message ?: "invalid")
        }
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = "Request parameter validation failed",
            fieldErrors = fieldErrors.takeIf { it.isNotEmpty() },
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicateEmail(ex: DuplicateEmailException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.CONFLICT.value(),
            error = "Conflict",
            message = ex.message ?: "Email already registered",
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.UNAUTHORIZED.value(),
            error = "Unauthorized",
            message = ex.message ?: "Invalid email or password",
        )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body)
    }

    @ExceptionHandler(
        DeviceNotFoundException::class,
        RestaurantNotFoundException::class,
        AssignmentNotFoundException::class,
        AdNotFoundException::class,
    )
    fun handleNotFound(ex: RuntimeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Resource not found",
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    /**
     * AC 3의 Sub-AC 2: 크로스 필드 스케줄 검증 실패(예:
     * `endTime <= startTime`). `MethodArgumentNotValidException`과 동일한
     * JSON 모양으로 표면화되므로, 규칙이 단일 필드 어노테이션이든 서비스
     * 레이어에서 파생된 불변식이든 관계없이 관리자 UI가 문제 필드에 대해
     * 균일하게 오류를 렌더링할 수 있음.
     */
    @ExceptionHandler(InvalidScheduleException::class)
    fun handleInvalidSchedule(ex: InvalidScheduleException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Schedule validation failed",
            fieldErrors = ex.fieldErrors.takeIf { it.isNotEmpty() },
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * AC 9, Sub-AC 1: `PATCH /api/devices/{deviceId}` 본문이 서버가 와이어
     * 레이어에서는 이해하지만 아직 영속화할 수 없는 필드를 참조한 경우
     * (예: V10 `devices` 테이블에 해당 컬럼이 자라기 전의 `screenName`).
     * 422 Unprocessable Entity로 매핑되어 관리자 UI가 "오타를 보냈다"(400)와
     * "서버는 필드를 받았지만 저장소가 준비되지 않았다"를 구분할 수 있음.
     * 핸들러는 문제 필드 이름을 표면화하여 클라이언트가 폼 입력에 대해
     * 정확한 오류를 렌더링할 수 있게 함.
     */
    @ExceptionHandler(DeviceFieldUnsupportedException::class)
    fun handleDeviceFieldUnsupported(ex: DeviceFieldUnsupportedException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
            error = "Unprocessable Entity",
            message = ex.message ?: "Device field not yet supported",
            fieldErrors = mapOf(ex.field to "not yet supported"),
        )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Invalid request",
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Sub-AC 3: 빈 multipart 본문과 누락된 파일명은 400 Bad Request로 매핑되는
     * 클라이언트 오류.
     */
    @ExceptionHandler(
        EmptyVideoUploadException::class,
        MissingVideoFilenameException::class,
    )
    fun handleVideoUploadBadRequest(ex: RuntimeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Invalid video upload",
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Sub-AC 3: MP4가 아닌 (또는 `Content-Type`이 없는) 업로드는 415
     * Unsupported Media Type. 이를 400과 구분하면 관리자 UI가 일반적인
     * "validation failed" 대신 미디어 전용 오류 토스트를 렌더링할 수 있음.
     */
    @ExceptionHandler(InvalidVideoMimeTypeException::class)
    fun handleInvalidVideoMimeType(ex: InvalidVideoMimeTypeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
            error = "Unsupported Media Type",
            message = ex.message ?: "Unsupported video MIME type",
        )
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body)
    }

    /**
     * Sub-AC 3: 크기 초과 업로드는 413 Payload Too Large로 매핑됨. 서비스는
     * 디스크에 쓰기 *전에* 이를 검사하므로 응답이 빠르고 서버 파일시스템에
     * 영향이 없음.
     */
    @ExceptionHandler(VideoTooLargeException::class)
    fun handleVideoTooLarge(ex: VideoTooLargeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.PAYLOAD_TOO_LARGE.value(),
            error = "Payload Too Large",
            message = ex.message ?: "Uploaded video exceeds the size limit",
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body)
    }

    /**
     * Sub-AC 5: 컨테이너 수준의 크기 초과. multipart 업로드가
     * `spring.servlet.multipart.max-file-size` (또는 `max-request-size`)를
     * 초과하면, 스프링은 요청이 `VideoUploadService`의 타입화된 검증에
     * 도달하기 *전에* 파싱을 중단하고 [MaxUploadSizeExceededException]을
     * 발생시킴. 명시적 핸들러가 없으면 예외가 catch-all `Exception` 매퍼로
     * 떨어져 오해를 일으키는 500을 반환함. 어느 레이어(서블릿 파서 vs
     * 애플리케이션 서비스)가 오버플로를 감지했는지에 무관하게 API 계약을
     * [VideoTooLargeException]과 일관되게 유지하기 위해 413으로 매핑함.
     *
     * 노트: 응답 메시지는 YAML 사이즈 문자열을 다시 파싱하지 않고 예외
     * 자체에서 설정된 multipart 상한을 의도적으로 표면화함(`maxUploadSize`는
     * 바이트 — 클라이언트가 신경 쓰는 동일 단위). 사람이 읽을 수 있는
     * 상한이 필요한 호출자는 스프링 설정 엔드포인트나 API 문서를 확인해야 함.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(ex: MaxUploadSizeExceededException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.PAYLOAD_TOO_LARGE.value(),
            error = "Payload Too Large",
            message = "Uploaded file exceeds the maximum allowed size " +
                "(max-upload-size=${ex.maxUploadSize} bytes)",
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body)
    }

    /**
     * Sub-AC 5: 다른 모든 multipart 파싱 실패(잘못된 경계, 잘린 본문,
     * 스트리밍 중 IO 오류)가 여기에 도달. 400으로 처리 — 클라이언트가 보낸
     * 바이트를 유효한 multipart 봉투로 파싱할 수 없었음. 사이즈 상한 하위
     * 클래스에 대해 더 구체적인 413 매핑이 우선하도록 위의
     * [MaxUploadSizeExceededException] 핸들러 *뒤에* 등록됨; 스프링은 선언
     * 순서와 무관하게 가장 구체적인 `@ExceptionHandler`를 먼저 매칭하지만,
     * 명확성을 위해 인접하게 유지함.
     */
    @ExceptionHandler(MultipartException::class)
    fun handleMultipart(ex: MultipartException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Malformed multipart request",
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Sub-AC 4: 필수 `file` 파트가 누락된 `multipart/form-data` 요청은
     * 클라이언트 오류 → 400 Bad Request. 이 핸들러가 없으면 예외가 아래의
     * 일반 `Exception` 매퍼로 떨어져 오해를 일으키는 500을 반환함.
     */
    @ExceptionHandler(
        MissingServletRequestPartException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleMissingPart(ex: Exception): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Required request part is missing",
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Sub-AC 4: `Content-Type`이 컨트롤러의 `consumes` 절과 일치하지 않는
     * 요청(예: multipart 전용 `POST /api/videos` 엔드포인트로 잘못된
     * `application/json` POST)은 415로 매핑되어야 함 — 이 조건의 표준 HTTP
     * 상태. 이 명시적 핸들러가 없으면 catch-all `Exception` 매퍼가 원인을
     * 500 뒤로 가림.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(ex: HttpMediaTypeNotSupportedException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
            error = "Unsupported Media Type",
            message = ex.message ?: "Unsupported request Content-Type",
        )
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body)
    }

    /**
     * AC 14: 스트리밍 엔드포인트(`GET /api/videos/{filename}`)는 요청된
     * 파일명과 일치하는 행이 없거나 행이 삽입된 이후 디스크 상의 바이트가
     * 제거된 경우 [VideoNotFoundException]을 발생시킴. 두 경우 모두 플레이어
     * 페이지 관점에서 404 Not Found — 바이트 스트림이 실제로 사용 불가.
     */
    @ExceptionHandler(VideoNotFoundException::class)
    fun handleVideoNotFound(ex: VideoNotFoundException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Video not found",
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    /**
     * AC 14: 클라이언트가 대상 파일에 대해 만족시킬 수 없는(예: EOF를 넘은
     * 시작 오프셋) 구문상 유효한 `Range` 헤더를 보냄. RFC 7233 §4.4는
     * 클라이언트가 유효한 range를 재발행할 수 있도록 `Content-Range:
     * bytes * /size`와 함께 416 응답을 의무화함. 헤더는 컨트롤러가 아닌
     * 여기서 설정되어 어떤 코드 경로가 예외를 발생시키든 무관하게 계약이
     * 균일하게 강제됨.
     */
    @ExceptionHandler(UnsatisfiableRangeException::class)
    fun handleUnsatisfiableRange(ex: UnsatisfiableRangeException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value(),
            error = "Range Not Satisfiable",
            message = ex.message ?: "Range not satisfiable",
        )
        return ResponseEntity
            .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            .header(HttpHeaders.CONTENT_RANGE, "bytes */${ex.fileSize}")
            .body(body)
    }

    /**
     * Jackson 수준 JSON 파싱 실패를 캐치 — 알 수 없는 enum 식별자(예:
     * `STARTED` / `FINISHED`만 허용하는
     * [me.owldev.adsignage.domain.playevent.PlayEventType]에 대한
     * `eventType: "PAUSED"`)와 `HH:mm` 필드의 `25:00` 같은 범위 초과
     * `@JsonFormat` 값의 일반적 케이스 포함.
     *
     * 이 명시적 핸들러가 없으면 예외가 아래의 catch-all `Exception` 매퍼로
     * 떨어져 API가 오해를 일으키는 500을 반환함. DTO 독스트링(예:
     * [me.owldev.adsignage.domain.ad.dto.UpdateAdScheduleRequest])은 이미
     * 이 종류의 실패에 대해 400 계약을 약속함; 이 핸들러가 약속을 행위로
     * 매핑함.
     *
     * 예외 메시지는 그대로 표면화되어 운영자가 서버 로그를 활성화하지 않고도
     * 관리자 UI가 Jackson의 경로 인식 진단("Cannot deserialize value of
     * type X from String 'Y'")을 렌더링할 수 있음.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.mostSpecificCause.message ?: ex.message ?: "Malformed request body",
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Spring 6.x의 기본 동작: 매핑된 컨트롤러가 없는 경로는 정적 리소스로
     * 시도되어 [NoResourceFoundException] 으로 끝남. 이걸 catch-all 500 으로
     * 떨어뜨리지 않고 명시적 404 로 매핑한다 — 그래야 어드민/프론트가
     * "엔드포인트가 없다"를 정확히 인식하고 사용자에게 적절한 메시지를
     * 보여준다.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = "No endpoint mapped for ${ex.resourcePath}",
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception", ex)
        val body = ApiError(
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            error = "Internal Server Error",
            message = ex.message ?: "Unexpected error",
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }
}
