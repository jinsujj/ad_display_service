package me.owldev.adsignage.bounded.context.restaurant.adapter.`in`.api

import me.owldev.adsignage.bounded.context.restaurant.application.service.RestaurantService
import me.owldev.adsignage.bounded.context.restaurant.domain.dto.RestaurantListItem
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 매핑 드롭다운용 음식점 목록 엔드포인트.
 *
 *   GET /api/restaurants
 *     광고주/운영자 어드민이 디바이스를 음식점에 매핑할 때 호출. JWT 필요
 *     (SecurityConfig 기본 정책). 응답은 이름 오름차순으로 정렬된 음식점
 *     리스트.
 */
@RestController
@RequestMapping("/api/restaurants")
class RestaurantController(
    private val restaurantService: RestaurantService,
) {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): ResponseEntity<List<RestaurantListItem>> =
        ResponseEntity.ok(restaurantService.listAll())
}
