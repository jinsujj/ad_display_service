package me.owldev.adsignage.web

import jakarta.validation.ConstraintViolationException
import me.owldev.adsignage.auth.DuplicateEmailException
import me.owldev.adsignage.auth.InvalidCredentialsException
import me.owldev.adsignage.domain.ad.AdNotFoundException
import me.owldev.adsignage.domain.ad.InvalidScheduleException
import me.owldev.adsignage.domain.assignment.AssignmentNotFoundException
import me.owldev.adsignage.domain.assignment.DeviceFieldUnsupportedException
import me.owldev.adsignage.domain.assignment.DeviceNotFoundException
import me.owldev.adsignage.domain.assignment.RestaurantNotFoundException
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
import java.time.Instant

/**
 * Centralised mapping of exceptions to HTTP responses.
 *
 * Sub-AC 2 cases:
 *  - bean-validation failures from `@Valid` → 400 with field error map
 *  - email collisions on signup                → 409 Conflict
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
     * Sub-AC 5: jakarta-validation `@Validated` constraints on path/query
     * parameters (or service-method arguments) raise
     * [ConstraintViolationException] rather than the
     * [MethodArgumentNotValidException] that `@Valid` on a `@RequestBody`
     * triggers. Both should surface as 400 with the same shape so callers can
     * render field-level errors uniformly regardless of which annotation
     * Spring picked up.
     *
     * The `propertyPath` is reduced to its last segment (e.g.
     * `getDevice.deviceId` → `deviceId`) so the response matches the field
     * name the client actually submitted.
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
     * Sub-AC 2 of AC 3: cross-field schedule validation failure (e.g.
     * `endTime <= startTime`). Surfaces with the same JSON shape as a
     * `MethodArgumentNotValidException` so the admin UI can render the
     * error against the offending field uniformly regardless of whether
     * the rule was a single-field annotation or a derived service-layer
     * invariant.
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
     * AC 9, Sub-AC 1: a `PATCH /api/devices/{deviceId}` body referenced a
     * field the server understands at the wire layer but cannot yet persist
     * (e.g. `screenName` before the V10 `devices` table grows that column).
     * Mapped to 422 Unprocessable Entity so the admin UI can disambiguate
     * "I sent a typo" (400) from "the server got my field but storage is not
     * ready". The handler surfaces the offending field name so clients can
     * render a precise error against the form input.
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
     * Sub-AC 3: empty multipart bodies and missing filenames are client
     * errors mapped to 400 Bad Request.
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
     * Sub-AC 3: a non-MP4 (or `Content-Type`-less) upload is a 415
     * Unsupported Media Type. Distinguishing this from 400 lets the admin
     * UI render a media-specific error toast instead of a generic
     * "validation failed".
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
     * Sub-AC 3: oversize uploads map to 413 Payload Too Large. The service
     * checks this *before* writing to disk so the response is fast and the
     * server's filesystem is not impacted.
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
     * Sub-AC 5: container-level oversize. When a multipart upload exceeds
     * `spring.servlet.multipart.max-file-size` (or `max-request-size`), Spring
     * aborts the parse *before* the request reaches `VideoUploadService`'s
     * typed validation, raising [MaxUploadSizeExceededException]. Without an
     * explicit handler the exception falls through to the catch-all `Exception`
     * mapper and returns a misleading 500. We map it to 413 to keep the API
     * contract consistent with [VideoTooLargeException] regardless of which
     * layer (servlet parser vs. application service) detected the overflow.
     *
     * Note: the response message intentionally surfaces the configured
     * multipart cap from the exception itself (`maxUploadSize` is bytes — the
     * same units the client cares about) rather than re-parsing the YAML
     * size string. Callers that need a human-readable cap should inspect the
     * Spring config endpoint or the API docs.
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
     * Sub-AC 5: any other multipart parse failure (malformed boundary,
     * truncated body, IO error during streaming) lands here. Treat it as a
     * 400 — the bytes the client sent could not be parsed as a valid
     * multipart envelope. This is registered *after* the
     * [MaxUploadSizeExceededException] handler above so the more specific
     * 413 mapping wins for the size-cap subclass; Spring matches the most
     * specific `@ExceptionHandler` first regardless of declaration order, but
     * we keep them adjacent for clarity.
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
     * Sub-AC 4: a `multipart/form-data` request that is missing the required
     * `file` part is a client error → 400 Bad Request. Without this handler
     * the exception falls through to the generic `Exception` mapper below
     * and returns a misleading 500.
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
     * Sub-AC 4: requests whose `Content-Type` does not match the controller's
     * `consumes` clause (e.g. a stray `application/json` POST to the
     * multipart-only `POST /api/videos` endpoint) should map to 415 — the
     * standard HTTP status for this condition. Without this explicit handler
     * the catch-all `Exception` mapper would mask the cause behind a 500.
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
     * AC 14: the streaming endpoint (`GET /api/videos/{filename}`) raises
     * [VideoNotFoundException] when no row matches the requested filename or
     * when the on-disk bytes have been removed since the row was inserted.
     * Both cases are 404 Not Found from the player page's perspective — the
     * byte stream genuinely is not available.
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
     * AC 14: client sent a syntactically valid `Range` header that cannot be
     * satisfied for the target file (e.g. a start offset past EOF). RFC 7233
     * §4.4 mandates a 416 response with `Content-Range: bytes * /size` so the
     * client can re-issue a valid range. The header is set here rather than
     * in the controller so the contract is enforced uniformly regardless of
     * which code path raises the exception.
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
     * Catches Jackson-level JSON parse failures — including the common case
     * of an unknown enum discriminator (e.g. `eventType: "PAUSED"` against
     * an [me.owldev.adsignage.domain.playevent.PlayEventType] that only
     * accepts `STARTED` / `FINISHED`) and out-of-range `@JsonFormat` values
     * like `25:00` for an `HH:mm` field.
     *
     * Without this explicit handler the exception falls through to the
     * catch-all `Exception` mapper below and the API returns a misleading
     * 500. The DTO docstrings (e.g. [me.owldev.adsignage.domain.ad.dto.UpdateAdScheduleRequest])
     * already promise a 400 contract for this class of failure; this maps
     * the promise to behaviour.
     *
     * The exception's message is surfaced verbatim so the admin UI can
     * render Jackson's path-aware diagnostic ("Cannot deserialize value of
     * type X from String 'Y'") without the operator having to enable
     * server logs.
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
