package me.owldev.adsignage.domain.video

import me.owldev.adsignage.auth.jwt.AdvertiserPrincipal
import me.owldev.adsignage.domain.video.dto.VideoResponse
import me.owldev.adsignage.domain.video.upload.EmptyVideoUploadException
import me.owldev.adsignage.domain.video.upload.VideoUploadService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 광고주가 업로드한 비디오 자산을 위한 REST 엔드포인트.
 *
 * Sub-AC 4 범위 — `POST /api/videos`:
 *  - **Multipart/form-data 전용.** 엔드포인트는
 *    `consumes = MULTIPART_FORM_DATA_VALUE`를 광고; 다른 콘텐츠 타입은
 *    이 컨트롤러에 도달하기 전에 스프링이 415로 거부함.
 *  - **`file` 파트 필수.** multipart `name`은 [VideoUploadServiceTest]와
 *    관리자 UI fetch 호출에서 사용하는 관례에 맞춰 `file`. 누락된 파트는
 *    400으로 매핑됨(스프링의 `MissingServletRequestPartException` 기본값이
 *    400) 빈 본문은 [EmptyVideoUploadException]을 통해
 *    [me.owldev.adsignage.web.GlobalExceptionHandler]에 의해 400으로 매핑됨.
 *  - **검증은 서비스에 위치.** [VideoUploadService.upload]가 디스크에
 *    바이트를 쓰기 *전에* MIME과 크기 검사를 실행하고, 전역 예외 핸들러가
 *    400 / 413 / 415로 매핑하는 타입화된 예외
 *    ([me.owldev.adsignage.domain.video.upload.VideoUploadException] 하위 클래스)를
 *    발생시킴.
 *  - **저장 + 영속화는 위임됨.** 서비스가
 *    [me.owldev.adsignage.domain.video.storage.VideoStorageService](바이트
 *    쓰기)를 [VideoRepository](행 쓰기)와 단일 트랜잭션 내에서 조합 —
 *    컨트롤러는 HTTP 모양에만 책임짐.
 *  - **[VideoResponse]와 함께 201 Created 반환**. DTO는 서버 생성 id,
 *    디스크 상 파일명, MIME 타입, 크기, 정규 스트리밍 URL, 업로드
 *    타임스탬프를 운반 — 관리자 UI가 재조회 없이 새 행을 렌더링하는 데
 *    필요한 모든 것.
 *
 * AC 4 — auth-and-isolation 패스:
 *  - `POST /api/videos`와 `GET /api/videos` 둘 다 인증된 광고주를 요구.
 *    JWT의 `sub` 클레임은 [me.owldev.adsignage.auth.jwt.JwtAuthenticationFilter]에
 *    의해 [AdvertiserPrincipal]로 구체화되며, 스프링 시큐리티의
 *    `@AuthenticationPrincipal` 인자 리졸버를 통해 두 메서드에 주입됨 —
 *    이것이 컨트롤러에서 `SecurityContextHolder`를 거치지 않고 검증된
 *    신원을 읽는 정규 방법.
 *  - 스트리밍 엔드포인트(`GET /api/videos/{filename}`, 형제 컨트롤러가
 *    서비스)는 미인증 플레이어 페이지가 MP4 바이트를 가져올 수 있도록
 *    공개로 유지; *부모* `/api/videos` 리스트만
 *    [me.owldev.adsignage.config.SecurityConfig]의 좁혀진 `permitAll()`
 *    ant 패턴으로 잠금(`/api/videos/{star}{star}` 대신
 *    `/api/videos/{star}`; Kotlin의 중첩 가능 블록 주석이 리터럴
 *    `/{star}{star}`를 중첩 KDoc 시작으로 취급하므로 의도적으로 별표를
 *    단어로 설명함).
 *  - 소유권 격리는 repository 호출 사이트에서 강제:
 *    [VideoRepository.findAllByAdvertiserIdOrderByUploadedAtDesc]는
 *    `advertiser_id`가 JWT가 주장한 id와 동일한 행만 반환. 술어가 요청
 *    파라미터가 아닌 검증된 principal에 바인딩되어 있어 크로스 광고주
 *    id 추측은 불가능.
 */
@RestController
@RequestMapping("/api/videos")
class VideoController(
    private val videoUploadService: VideoUploadService,
    private val videoRepository: VideoRepository,
) {

    private val log = LoggerFactory.getLogger(VideoController::class.java)

    /**
     * *로그인한 광고주의* 업로드된 비디오를 최신 순으로 나열.
     *
     * 인가 계약 (AC 4):
     *  - JWT가 없거나 잘못된 경우 이 메서드에 도달하기 전에 스프링
     *    시큐리티가 이미 요청을 거부함 — 따라서 핸들러가 실행될 때
     *    [AdvertiserPrincipal] 파라미터는 non-null이 보장됨. 잘못 설정된
     *    `permitAll()` 규칙으로 익명 요청이 통과하면 *모든* 광고주의
     *    비디오를 반환하는 대신 fail-fast(NPE / 500)되도록 non-nullable로
     *    선언함.
     *
     * 정렬 순서:
     *  - `uploaded_at DESC` — 방금 "Upload"를 누른 운영자는 새 행이 맨
     *    위에 보이기를 기대함. V31에서 추가된 복합 인덱스
     *    `(advertiser_id, uploaded_at)`로 계획자가 정렬 단계 없이 WHERE와
     *    ORDER BY 둘 다 처리할 수 있게 됨.
     *
     * 빈 상태 계약:
     *  - 이 광고주가 아직 비디오를 업로드하지 않은 경우 빈 JSON 배열과
     *    함께 `200 OK`를 반환. 관리자 UI는 "결과 없음"을 오류로 취급하지
     *    않고 빈 상태를 렌더링하기 위해 `[]`에 의존함.
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<List<VideoResponse>> {
        val videos = videoRepository
            .findAllByAdvertiserIdOrderByUploadedAtDesc(principal.advertiserId)
        log.info(
            "GET /api/videos advertiserId={} returning {} video(s)",
            principal.advertiserId, videos.size,
        )
        return ResponseEntity.ok(videos.map(VideoResponse::from))
    }

    /**
     * multipart/form-data로 단일 MP4 업로드를 받아 영속화된 [Video]를
     * [VideoResponse]로 반환.
     *
     * 왜 `@RequestPart` 대신 `@RequestParam`인가:
     *  - 일반 파일 파트의 경우 `@RequestParam("file") MultipartFile`이
     *    관용적인 스프링 바인딩이며, 파트가 누락되었을 때 가장 깔끔한
     *    오류 메시지를 생성함(스프링의
     *    `MissingServletRequestParameterException` → 400). `@RequestPart`는
     *    JSON 변환이 필요한 파트(예: 파일과 함께 있는 메타데이터 객체)를
     *    위한 것이며, 우리는 그것이 없음.
     *  - multipart 이름 `file`은 기존 서비스 테스트 픽스처
     *    (`MockMultipartFile("file", …)`)를 미러링하여 관리자 UI의
     *    `FormData.append("file", blob)` 호출이 서버 측 이름 변경 없이
     *    동작하게 함.
     *
     * 소유권 스탬핑 (AC 4):
     *  - 검증된 [AdvertiserPrincipal]이 [VideoUploadService.upload]로
     *    전달되어 영속화된 행이 JWT가 주장한 소유자 id를 운반. (컨트롤러가
     *    `Video`를 직접 빌드하지 않고) 서비스 경계에서 스탬핑하면 향후
     *    코드 경로가 컬럼을 설정하지 않는 것을 방지 — 서비스 시그니처가
     *    id를 요구함.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AdvertiserPrincipal,
    ): ResponseEntity<VideoResponse> {
        log.info(
            "POST /api/videos advertiserId={} originalName='{}' contentType={} size={}",
            principal.advertiserId, file.originalFilename, file.contentType, file.size,
        )
        val saved = videoUploadService.upload(file, principal.advertiserId)
        log.info(
            "video upload succeeded id={} filename={} advertiserId={}",
            saved.id, saved.filename, saved.advertiserId,
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(VideoResponse.from(saved))
    }
}
