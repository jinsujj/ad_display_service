package me.owldev.adsignage.bounded.context.ad.adapter.out.database

import me.owldev.adsignage.bounded.context.ad.domain.model.Ad
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface AdRepository : JpaRepository<Ad, String> {

    /**
     * id가 [id]이고 [Ad.advertiserId]가 [advertiserId]인 [Ad]를 반환.
     * 어느 조건이라도 실패하면 비어 있음 — 컨트롤러가 비어 있음을 404로
     * 매핑하므로 다른 광고주의 광고 존재 여부를 절대 누설하지 않음.
     */
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Optional<Ad>

    /**
     * [advertiserId]가 소유한 모든 광고를 최신 순으로 반환. 관리자
     * 대시보드의 "내 광고" 리스팅을 지원 — V40에서 제공된
     * `idx_ads_advertiser_id` 인덱스가 WHERE를 커버; ORDER BY는 보조
     * `idx_ads_created_at`(또는 계획자가 선호하면 정렬 단계)을 사용.
     */
    fun findAllByAdvertiserIdOrderByCreatedAtDesc(advertiserId: String): List<Ad>
}
