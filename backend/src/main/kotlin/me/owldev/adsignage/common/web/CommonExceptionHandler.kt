package me.owldev.adsignage.common.web

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
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

/**
 * Spring Framework / 서블릿 컨테이너가 던지는 *도메인 무관* 예외에 대한
 * 중앙 매핑. 도메인 예외는 각 컨텍스트의 `{Ctx}ExceptionHandler` 가 처리.
 *
 * 처리 대상:
 *  - 본문/파라미터 검증 실패 (`@Valid`, `@Validated`)
 *  - JSON 파싱 실패 (잘못된 enum 값, 범위 초과 `@JsonFormat`)
 *  - multipart 파싱 / 크기 초과 / 누락된 파트
 *  - 잘못된 Content-Type / 매핑되지 않은 라우트
 *  - 일반 IllegalArgumentException
 *  - 마지막 catch-all
 */
@RestControllerAdvice
class CommonExceptionHandler {

    private val log = LoggerFactory.getLogger(CommonExceptionHandler::class.java)

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
     * 경로/쿼리 파라미터 (또는 서비스 메서드 인자) 에 대한 jakarta-validation
     * `@Validated` 제약은 `@RequestBody` 의 `@Valid` 가 트리거하는
     * [MethodArgumentNotValidException] 대신 [ConstraintViolationException]
     * 을 발생시킴. 둘 다 동일한 모양의 400 으로 표면화되어야 호출자가
     * 균일하게 렌더링할 수 있음.
     *
     * `propertyPath` 는 마지막 세그먼트로 축소(예: `getDevice.deviceId` →
     * `deviceId`).
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
     * 컨테이너 수준의 multipart 크기 초과 — 스프링이 요청을 파서 단계에서
     * 끊고 [MaxUploadSizeExceededException] 을 발생시킴. 명시적 핸들러가
     * 없으면 catch-all 500 으로 떨어지므로 413 으로 매핑.
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
     * 다른 모든 multipart 파싱 실패 — 잘못된 경계, 잘린 본문, 스트리밍 중 IO 오류.
     * 사이즈 상한 하위 클래스에 대한 [MaxUploadSizeExceededException] 핸들러가
     * 더 구체적으로 우선 매칭됨.
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
     * 필수 multipart 파트 / 쿼리 파라미터 누락은 클라이언트 오류 → 400.
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
     * `Content-Type` 이 컨트롤러의 `consumes` 절과 일치하지 않는 요청 → 415.
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
     * Jackson 수준 JSON 파싱 실패 — 알 수 없는 enum 식별자, 범위 초과
     * `@JsonFormat` 값 등.
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
     * Spring 6.x 기본: 매핑된 컨트롤러가 없는 경로는 정적 리소스로 시도되어
     * [NoResourceFoundException] 으로 끝남. catch-all 500 대신 명시적 404.
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
