package me.owldev.adsignage.bounded.context.restaurant.domain.dto

data class RestaurantListItem(
    val restaurantId: String,
    val restaurantName: String,
    val address: String?,
)
