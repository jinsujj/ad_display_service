package me.owldev.adsignage.common.web

import java.time.Instant

/**
 * 모든 컨텍스트의 ExceptionHandler 가 공유하는 와이어 응답 모양.
 *
 * 구조 변경(예: 새 필드 추가)은 모든 핸들러에 한 번에 반영되도록 한 곳에서
 * 관리. fieldErrors 는 Bean Validation / 도메인 cross-field 룰 위반 모두에서
 * 동일한 형태로 표면화되어 클라이언트가 한 가지 파서로 처리할 수 있다.
 */
data class ApiError(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String,
    val fieldErrors: Map<String, String>? = null,
)
