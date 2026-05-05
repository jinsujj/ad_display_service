package me.owldev.adsignage.bounded.context.restaurant.application.service

import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import me.owldev.adsignage.bounded.context.restaurant.domain.dto.RestaurantListItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RestaurantService(
    private val restaurantRepositoryPort: RestaurantRepositoryPort,
) {
    /**
     * 매핑 드롭다운/리스트용 음식점 목록을 이름 정렬로 반환. 어드민 UI 의
     * 단일 진입점 — `GET /api/restaurants` 가 그대로 위임한다.
     */
    @Transactional(readOnly = true)
    fun listAll(): List<RestaurantListItem> =
        restaurantRepositoryPort.findAllByOrderByName().map {
            RestaurantListItem(
                restaurantId = it.restaurantId,
                restaurantName = it.name,
                address = it.address,
            )
        }
}
