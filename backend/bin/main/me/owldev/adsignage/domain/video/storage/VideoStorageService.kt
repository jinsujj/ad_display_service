package me.owldev.adsignage.domain.video.storage

import org.springframework.web.multipart.MultipartFile

/**
 * 업로드된 영상 파일을 백킹 스토어에 영속화하고, 호출자가
 * [me.owldev.adsignage.domain.video.Video] 행을 만들고 스트리밍 엔드포인트를
 * 통해 파일을 노출하는 데 필요한 메타데이터를 반환한다.
 *
 * 인터페이스는 의도적으로 백엔드에 비종속 — 해커톤은 로컬 파일시스템
 * 구현([LocalVideoStorageService])을 출시하지만, 동일한 계약을 추후
 * 업로드 컨트롤러나 [me.owldev.adsignage.domain.video.Video] 엔터티를
 * 건드리지 않고 S3 / GCS로 백킹할 수 있다.
 *
 * Sub-AC 2 계약:
 *  - 스프링 [MultipartFile]을 받음(이 서비스가 뒤에 위치할 업로드
 *    엔드포인트의 자연스러운 입력).
 *  - 두 광고주가 `promo.mp4`를 업로드해도 디스크 상에서 충돌하지 않도록
 *    서버 통제 [StoredVideo.filename]을 생성.
 *  - [StoredVideo.storagePath]를 그대로 절대 경로로 반환 — 엔터티 레이어는
 *    설정 루트에 대해 다시 해석하지 않고 저장하며, 이는 `Video.kt`에 문서화된
 *    의도와 일치.
 *  - 플레이어 플레이리스트에 적합한 [StoredVideo.urlPath]를 반환; 실제
 *    라우트는 스트리밍 엔드포인트가 소유하고, 이 서비스는 정식 URL 형태
 *    (`/api/videos/{filename}`)를 예측만 함.
 */
interface VideoStorageService {

    /**
     * [file]을 설정된 스토리지 루트 아래에 영속화한다.
     *
     * @throws IllegalArgumentException 업로드가 비어있거나, 사용 가능한
     *   원본 파일명이 없거나, 미지원 확장자일 때.
     */
    fun store(file: MultipartFile): StoredVideo
}

/**
 * 성공한 업로드의 결과 — 호출자가 [me.owldev.adsignage.domain.video.Video]
 * 엔터티를 구성하고 *그리고* 플레이어 플레이리스트에 영상을 노출하는 데
 * 필요한 모든 필드.
 *
 *  - [filename]      서버 생성, 디스크 상 유일; URL에 노출해도 안전.
 *  - [originalName]  살균된 광고주 제공 파일명, 어드민 UI 용도.
 *  - [mimeType]      스트리밍 엔드포인트가 서빙할 Content-Type.
 *  - [sizeBytes]     파일 *기록 후* 측정(권위 있는 값).
 *  - [storagePath]   절대 디스크 경로; 앱의 나머지에는 불투명.
 *  - [urlPath]       정식 스트리밍 URL(예: `/api/videos/{filename}`).
 */
data class StoredVideo(
    val filename: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val urlPath: String,
)
