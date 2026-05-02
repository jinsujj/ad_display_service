package me.owldev.adsignage.domain.video

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * [Video]를 위한 JPA repository.
 *
 * [Video.filename]에 의한 조회는 스트리밍 엔드포인트
 * (`GET /api/videos/{filename}`)가 UUID가 아닌 디스크 상의 파일명을 경로
 * 변수로 받기 때문에 필요 — URL을 짧게 유지하고 플레이어 페이지가
 * 플레이리스트가 건네주는 동일 식별자로 비디오를 참조할 수 있게 함.
 * 스트리밍 경로는 공개(JWT 없음)로 유지되며 의도적으로 광고주별로
 * 필터링하지 *않음* — 플레이어는 할당된 음식점의 플레이리스트에 나타나는
 * 어떤 광고주의 비디오든 가져와야 함.
 *
 * [findAllByAdvertiserIdOrderByUploadedAtDesc]는 관리자 "업로드된 비디오"
 * 리스트 뷰(`GET /api/videos`)를 지원함. 로그인한 광고주의 id는 컨트롤러의
 * JWT principal에서 가져와 술어로 푸시되므로 쿼리 계획이 V31에서 추가된
 * `(advertiser_id, uploaded_at)` 복합 인덱스를 사용함 — WHERE와 ORDER BY
 * 모두 정렬 단계 없이 처리됨. 이것이 repository 측의 AC 4 데이터 격리
 * 계약.
 *
 * [findByIdAndAdvertiserId]는 정규 "이 단일 비디오를 가져오되, 내 것일
 * 때만" 조회. 추후의 단일 비디오 읽기/변경 엔드포인트(이름 변경, 삭제,
 * 스케줄 연결)가 이 메서드를 거쳐 크로스 광고주 id 추측이 임의 광고주의
 * 행이 아닌 `Optional.empty()`를 반환하도록 함.
 */
@Repository
interface VideoRepository : JpaRepository<Video, String> {
    fun findByFilename(filename: String): Optional<Video>
    fun existsByFilename(filename: String): Boolean
    fun findAllByAdvertiserIdOrderByUploadedAtDesc(advertiserId: String): List<Video>
    fun findByIdAndAdvertiserId(id: String, advertiserId: String): Optional<Video>
}
