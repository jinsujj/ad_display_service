package me.owldev.adsignage.bounded.context.assignment.adapter.`in`.api

import me.owldev.adsignage.bounded.context.assignment.domain.exception.AssignmentNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceFieldUnsupportedException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.DeviceNotFoundException
import me.owldev.adsignage.bounded.context.assignment.domain.exception.RestaurantNotFoundException
import me.owldev.adsignage.common.web.ApiError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Assignment 컨텍스트 도메인 예외 매핑.
 *  - DeviceNotFound / RestaurantNotFound / AssignmentNotFound → 404
 *  - DeviceFieldUnsupportedException → 422 (와이어 계약은 알지만 컬럼이 아직 없음)
 */
@RestControllerAdvice
class AssignmentExceptionHandler {

    @ExceptionHandler(
        DeviceNotFoundException::class,
        RestaurantNotFoundException::class,
        AssignmentNotFoundException::class,
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
     * AC 9, Sub-AC 1: `PATCH /api/devices/{deviceId}` 본문이 서버가 와이어
     * 레이어에서는 이해하지만 아직 영속화할 수 없는 필드를 참조한 경우 → 422.
     * "오타를 보냈다"(400)와 "서버는 필드를 받았지만 저장소가 준비되지 않았다"
     * 를 구분.
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
}
