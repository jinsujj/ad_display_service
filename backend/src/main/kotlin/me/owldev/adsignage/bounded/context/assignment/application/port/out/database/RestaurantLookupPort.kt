package me.owldev.adsignage.bounded.context.assignment.application.port.out.database

/**
 * restaurant 컨텍스트로의 inter-context 조회 포트. assignment 가 매핑을 만들 때
 * 참조된 restaurant_id 가 실제 존재하는지 검증해야 하는데, 그 진실의 원천은
 * restaurant 컨텍스트이다.
 */
interface RestaurantLookupPort {
    fun exists(restaurantId: String): Boolean
}
