package me.owldev.adsignage.bounded.context.assignment.adapter.out

import me.owldev.adsignage.bounded.context.assignment.application.port.out.database.RestaurantLookupPort
import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import org.springframework.stereotype.Component

/**
 * restaurant 컨텍스트의 [RestaurantRepositoryPort] 를 호출하여 restaurant 존재
 * 여부를 확인하는 inter-context 어댑터.
 */
@Component
class RestaurantLookupAdapter(
    private val restaurantRepositoryPort: RestaurantRepositoryPort,
) : RestaurantLookupPort {
    override fun exists(restaurantId: String): Boolean = restaurantRepositoryPort.existsById(restaurantId)
}
