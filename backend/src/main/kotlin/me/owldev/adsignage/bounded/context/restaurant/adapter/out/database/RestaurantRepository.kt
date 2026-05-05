package me.owldev.adsignage.bounded.context.restaurant.adapter.out.database

import me.owldev.adsignage.bounded.context.restaurant.domain.model.Restaurant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RestaurantRepository : JpaRepository<Restaurant, String> {
    fun findAllByOrderByName(): List<Restaurant>
}
