package me.owldev.adsignage.bounded.context.ad.adapter.`in`.api

import me.owldev.adsignage.bounded.context.ad.domain.exception.AdNotFoundException
import me.owldev.adsignage.bounded.context.ad.domain.exception.InvalidScheduleException
import me.owldev.adsignage.common.web.ApiError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Ad 컨텍스트 도메인 예외 매핑.
 *  - AdNotFoundException → 404 (auth-and-isolation: "당신 것 아님" 케이스도 404 로 축약)
 *  - InvalidScheduleException → 400 with fieldErrors (cross-field 룰 위반)
 */
@RestControllerAdvice
class AdExceptionHandler {

    @ExceptionHandler(AdNotFoundException::class)
    fun handleNotFound(ex: AdNotFoundException): ResponseEntity<ApiError> {
        val body = ApiError(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Ad not found",
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    /**
     * 크로스 필드 스케줄 검증 실패(예: `endTime <= startTime`) 를
     * `MethodArgumentNotValidException` 과 동일한 JSON 모양으로 표면화 —
     * 관리자 UI 가 단일 필드 어노테이션이든 서비스 레이어 불변식이든 균일하게
     * 렌더링.
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
}
