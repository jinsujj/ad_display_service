package me.owldev.adsignage.domain.video.dto

import com.fasterxml.jackson.annotation.JsonInclude
import me.owldev.adsignage.domain.video.Video
import java.time.Instant

/**
 * `POST /api/videos`가 반환하는 [Video]의 와이어 레벨 표현.
 *
 * Sub-AC 4 계약:
 *  - 컨트롤러는 JPA 엔터티 레이아웃과 무관한 안정적인 JSON 형태를 반환해야
 *    한다 — Hibernate 프록시와 `@OneToMany` 지연 컬렉션이 컨트롤러 경계
 *    밖으로 벗어날 이유가 없다.
 *  - 여기 노출되는 모든 필드는 어드민 UI / 플레이어 플레이리스트가 필요로
 *    하는 `videos` 테이블의 컬럼에 대응:
 *      - [id]           — 서버 생성 UUID(기본 키).
 *      - [filename]     — 서버 생성 디스크 상 파일명; 플레이어 페이지가
 *                        스트리밍 엔드포인트에서 가져올 [url]의 경로
 *                        세그먼트이기도 함.
 *      - [originalName] — 광고주 제공 파일명. 어드민 UI의 "최근 업로드"
 *                        목록에 사용.
 *      - [mimeType]     — Content-Type(예: `video/mp4`) — 플레이어의
 *                        `<video>` 요소가 디코더를 선택하는 데 사용.
 *      - [sizeBytes]    — 다시 stat된 디스크 상 바이트; UI 진행 표시기와
 *                        Range 요청 계산에 유용.
 *      - [url]          — 플레이어가 접두사를 다시 유도하지 않고 가져올
 *                        수 있는 정식 스트리밍 URL; 스토리지 레이어가
 *                        반환하는 `urlPath`를 미러링.
 *      - [uploadedAt]   — 삽입 시점 타임스탬프; 어드민 UI 정렬에 사용.
 *
 * `@JsonInclude(NON_NULL)`은 방어적: 현재 스키마에는 nullable 컬럼이
 * 없지만, 미래에 `String?` 필드가 추가될 때 떠도는 `"foo": null`이 계약을
 * 오염시키지 않도록 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VideoResponse(
    val id: String,
    val filename: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val url: String,
    val uploadedAt: Instant,
) {
    companion object {
        /** 스트리밍 엔드포인트가 위치하는 정식 URL 접두사. */
        private const val URL_PREFIX = "/api/videos"

        /**
         * 영속화된 [Video]에서 와이어 DTO를 만든다. 스트리밍 URL은
         * `filename`에서 유도된다 — `LocalVideoStorageService`가
         * [me.owldev.adsignage.domain.video.storage.StoredVideo.urlPath]에서
         * 예측하는 형태와 동일 — 따라서 어드민 UI와 플레이어 페이지가
         * 단일 정식 URL 형태를 본다.
         */
        fun from(entity: Video): VideoResponse = VideoResponse(
            id = entity.id,
            filename = entity.filename,
            originalName = entity.originalName,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            url = "$URL_PREFIX/${entity.filename}",
            uploadedAt = entity.uploadedAt,
        )
    }
}
