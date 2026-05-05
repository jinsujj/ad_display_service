package me.owldev.adsignage.bounded.context.restaurant.adapter.out.database

import me.owldev.adsignage.bounded.context.restaurant.application.port.out.database.RestaurantRepositoryPort
import me.owldev.adsignage.bounded.context.restaurant.domain.model.Restaurant
import org.springframework.stereotype.Repository

@Repository
class RestaurantRepositoryAdapter(
    private val restaurantRepository: RestaurantRepository,
) : RestaurantRepositoryPort {
    override fun findAll(): List<Restaurant> = restaurantRepository.findAll()
    override fun findAllByOrderByName(): List<Restaurant> = restaurantRepository.findAllByOrderByName()
    override fun existsById(restaurantId: String): Boolean = restaurantRepository.existsById(restaurantId)
}
