package me.owldev.adsignage.bounded.context.restaurant.application.port.out.database

import me.owldev.adsignage.bounded.context.restaurant.domain.model.Restaurant

/**
 * Restaurant 컨텍스트의 영속 포트. 외부 컨텍스트(예: ad, device)도 음식점
 * 정보가 필요한 핫패스에서 이 포트만 의존하도록 통일한다 — Spring Data
 * 인터페이스를 직접 들이지 않음으로써 컨텍스트 간 결합 깊이를 한 단계
 * 묶어 둔다.
 */
interface RestaurantRepositoryPort {
    fun findAll(): List<Restaurant>
    fun findAllByOrderByName(): List<Restaurant>
    fun existsById(restaurantId: String): Boolean
}
