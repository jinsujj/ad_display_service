package me.owldev.adsignage.domain.ad

/**
 * [AdService] 작업이 존재하지 *않거나* 존재하지만 호출 광고주가 소유하지
 * 않은 ad_id를 참조할 때 던져짐. [me.owldev.adsignage.web.GlobalExceptionHandler]에
 * 의해 HTTP 404로 매핑됨.
 *
 * "당신 것이 아님" 케이스는 의도적으로 "찾을 수 없음" 케이스로 축약됨 —
 * 서비스는 한 광고주에게 다른 광고주의 광고 id가 존재하는지를 절대
 * 알리지 않음으로써 AC 4 auth-and-isolation 계약을 충족시킴.
 */
class AdNotFoundException(val adId: String) :
    RuntimeException("Ad not found: $adId")

/**
 * 스케줄 페이로드가 빈 검증의 필드 수준 검사는 통과하지만 *크로스 필드*
 * 불변식을 실패할 때 던져짐 — 구체적으로 `endTime`이 `startTime`보다 엄격히
 * 뒤여야 함. 파생 속성에 대한 Bean Validation의 `@AssertTrue`도 동작하지만,
 * 타입화된 예외를 발생시키는 것이 서비스 시그니처에서 크로스 필드 규칙을
 * 발견 가능하게 유지하고 GlobalExceptionHandler가
 * `MethodArgumentNotValidException`이 생성하는 것과 동일한 깔끔한
 * field-error 맵을 표면화하게 해줌.
 *
 * 문제 필드(예: `endTime`)를 키로 하는 `fieldErrors` 맵과 함께 HTTP 400으로
 * 매핑됨.
 */
class InvalidScheduleException(
    val fieldErrors: Map<String, String>,
    message: String = "Schedule validation failed",
) : RuntimeException(message)
