package me.owldev.adsignage.domain.ad

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * [Ad]를 위한 Spring Data JPA repository.
 *
 * 변경 엔드포인트가 사용하는 모든 단일 행 읽기에 대해 "and advertiserId"
 * 변형을 의도적으로 노출. auth-and-isolation 계약(AC 4)은 광고주가 자신의
 * 광고만 보고/수정할 수 있다는 것 — id로 가져온 다음 Kotlin에서 비교하지
 * 않고 술어를 쿼리에 푸시하면 크로스 광고주 id 추측이 `Optional.empty()`를
 * 반환하고 호출자에게 404를 건넴. 컨트롤러를 통해 "이 id는 존재하지만
 * 당신 것이 아님" vs "이 id는 존재하지 않음"을 노출하는 경로가 없음 —
 * 둘 다 동일한 not-found 모양으로 축약됨.
 *
 * 지원되는 핫패스:
 *  - "이 단일 광고를 가져오되, 내 것일 때만"          → [findByIdAndAdvertiserId]
 *  - "내 모든 광고 나열, 최신 우선"(관리자 대시보드)  → [findAllByAdvertiserIdOrderByCreatedAtDesc]
 *  - 플레이리스트 계산은 광고 → 비디오를 파일명으로 조인 → 일반 [findAll] /
 *    추후 음식점별 쿼리로 처리; 스케줄 업데이트 엔드포인트에는 특별히
 *    필요하지 않음.
 */
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
