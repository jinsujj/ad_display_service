package me.owldev.adsignage.config

import me.owldev.adsignage.auth.jwt.JwtAccessDeniedHandler
import me.owldev.adsignage.auth.jwt.JwtAuthenticationEntryPoint
import me.owldev.adsignage.auth.jwt.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * AC 302의 Sub-AC 2 — 스프링 시큐리티 와이어링.
 *
 * API에 대한 단일 [SecurityFilterChain]을 정의:
 *
 *  - **Stateless** 세션 정책. API는 JWT-bearer 기반; `HttpSession`을 절대
 *    생성하거나 의존하지 않음. 이는 또한 스프링의 기본 SESSION 쿠키를
 *    비활성화 — 그렇지 않으면 nginx 뒤의 SSE 롱폴 플레이어를 혼란시킴.
 *  - **CSRF 비활성화.** CSRF는 쿠키 기반 자격증명만 보호; 우리는
 *    `Authorization: Bearer …`만 사용하고, 플레이어 페이지는 인증용 쿠키를
 *    쓰지 않음. CSRF 비활성화는 또한 관리자 웹 클라이언트가 사용하는
 *    JSON `POST/PUT` 계약도 풀어줌.
 *  - **HTTP Basic / 폼 로그인 비활성화.** 이들은 401에 기본
 *    `WWW-Authenticate: Basic` realm을 주입하고 우리가 원치 않는 /login
 *    폼 라우트를 추가함.
 *  - **공개 엔드포인트** (토큰 불필요):
 *      - `POST /api/auth/{...}` — 회원가입 + 로그인 (토큰 발급)
 *      - `GET  /actuator/health` — nginx / 로드 밸런서를 위한 헬스 프로브
 *      - `GET  /h2-console/{...}` — 개발 전용 DB 브라우저(또한 H2 웹 UI의
 *        프레임이 로드되도록 프레임 옵션을 `sameOrigin`으로 완화)
 *      - 플레이어 API — Next.js `/player/{deviceId}` 페이지는 JWT 없이
 *        백엔드를 호출(디바이스는 미인증; deviceId 경로 파라미터가
 *        해커톤에서 신원의 운반자). SSE 스트림 + 디바이스별 플레이리스트 +
 *        스트리밍 자식 경로 `GET /api/videos/{filename}`이 노출됨; 부모
 *        `GET /api/videos` 컬렉션(관리자 리스트)은 익명 요청이 AC 4의
 *        광고주별 소유권 필터를 우회하지 못하도록 허용 목록에 *없음*.
 *  - **그 외 모든 라우트**는 인증된 principal을 요구 — 유효한 bearer
 *    토큰이 제시되면 [JwtAuthenticationFilter]에 의해 상류에서 채워짐.
 *    아직 인증 계약으로 마이그레이션되지 않은 엔드포인트(예: 디바이스
 *    할당 관리자 라우트 —
 *    [me.owldev.adsignage.domain.assignment.DeviceAssignmentController] 참조)는
 *    과도기 조치로 명시적 허용 목록에 등록; 추후 auth-and-isolation 패스에서
 *    잠금 처리됨.
 *
 * **필터 순서.** [JwtAuthenticationFilter]는 [UsernamePasswordAuthenticationFilter]
 * *전에* 등록되어:
 *  1. JWT 검증 성공 시 SecurityContext가 채워짐.
 *  2. 이후 다운스트림 `authorizeHttpRequests` 규칙이 채워진 컨텍스트에 대해
 *     `.authenticated()`를 평가할 수 있음.
 *  3. 스프링의 기본 `UsernamePasswordAuthenticationFilter`(원래라면 `/login`에서
 *     `username`/`password` 폼 파라미터를 찾을 것)가 결코 우선권을 갖지
 *     않음 — 체인 내 위치는 우리의 JWT 필터를 위한 관례적 기준점으로만
 *     유지됨.
 */
@Configuration
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val jwtAuthenticationEntryPoint: JwtAuthenticationEntryPoint,
    private val jwtAccessDeniedHandler: JwtAccessDeniedHandler,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
            .logout { logout -> logout.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    // --- 공개: 인증 (로그인/회원가입이 JWT 발급) ----------
                    .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                    // --- 공개: 헬스 프로브 ---------------------------------
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    // --- 공개: 개발 전용 H2 콘솔 ---------------------------
                    .requestMatchers("/h2-console/**").permitAll()
                    // --- 공개: OpenAPI / Swagger UI ------------------------
                    // springdoc-openapi 가 노출하는 정적 자원과 JSON 스펙은
                    // 누구나 접근 가능해야 어드민/플레이어 개발자가 토큰
                    // 없이도 API를 탐색할 수 있다.
                    .requestMatchers(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                    ).permitAll()
                    // --- 공개: 플레이어 API (디바이스 측, JWT 없음) --------
                    // Next.js 플레이어가 구독하는 SSE 스트림.
                    // `/events` — 원래의 AC 5 SSE 와이어 (DeviceSseController).
                    // `/stream` — sub-AC 50002 SSE 와이어 (DeviceStreamController);
                    //            두 라우트가 모두 익명 디바이스 호출자에게
                    //            도달 가능하도록 형제 예외로 유지.
                    .requestMatchers(HttpMethod.GET, "/api/devices/*/events").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/devices/*/stream").permitAll()
                    // 플레이리스트 + 비디오 range 엔드포인트는 추후 sub-AC에서
                    // 도착; 접두사를 미리 허용해 해당 엔드포인트가 존재하는
                    // 즉시 플레이어 페이지를 배포 가능하게 유지.
                    .requestMatchers(HttpMethod.GET, "/api/devices/*/playlist").permitAll()
                    // AC 20202 Sub-AC 2: POST /api/devices/{id}/play-events
                    // — 플레이어가 "광고 시작" / "광고 종료"를 여기에 보고하여
                    // 백엔드가 서버 측 재생 횟수를 갱신할 수 있게 함.
                    // 형제 `…/stream` / `…/playlist` 라우트와 동일한 익명-
                    // 디바이스 계약; auth-and-isolation 패스가 디바이스 등록
                    // 토큰을 도입할 때까지 deviceId 경로 파라미터가 신원의
                    // 운반자.
                    .requestMatchers(HttpMethod.POST, "/api/devices/*/play-events").permitAll()
                    // AC 4 (auth-and-isolation): 스트리밍 엔드포인트
                    // `GET /api/videos/{filename}`은 공개 유지하여 플레이어
                    // 페이지가 JWT 없이 MP4 바이트를 가져올 수 있게 하되,
                    // *부모* `GET /api/videos`(관리자 리스트)는 소유권
                    // 필터링이 가능하도록 인증되어야 함. `/api/videos/**`이
                    // 아닌 `/api/videos/*`를 사용하면 정확히 한 개의 후행
                    // 경로 세그먼트와 매치하므로 컬렉션 URI는 `.authenticated()`로
                    // 떨어짐.
                    .requestMatchers(HttpMethod.GET, "/api/videos/*").permitAll()
                    // --- 과도기: 아직 인증 게이트가 없는 관리자 엔드포인트.
                    // auth-and-isolation 패스가 도착하면 ROLE_ADVERTISER가
                    // 필요. 체인에 JWT 필터를 연결하는 동안 그린 테스트
                    // 스위트를 그린으로 유지하기 위해 여기서는 열어둠.
                    .requestMatchers("/api/devices/*/assignment").permitAll()
                    // Sub-AC 50101.1: PATCH /api/devices/{deviceId}/restaurant
                    // — 데모 시나리오 #3을 위한 관리자 리매핑 진입점.
                    // 형제 `…/assignment` 라우트와 함께 과도기 예외로 허용;
                    // `/api/devices/**` 관리자 CRUD를 ROLE_ADVERTISER 뒤로
                    // 잠그는 auth-and-isolation 패스에서 잠금 처리됨.
                    .requestMatchers("/api/devices/*/restaurant").permitAll()
                    // AC 9, Sub-AC 1: PATCH /api/devices/{deviceId} —
                    // 디바이스 필드를 위한 일반 부분 업데이트(현재 restaurantId;
                    // V10 devices 테이블에 컬럼이 늘어나면 screen/group).
                    // 단일 세그먼트 매처라서 디바이스 루트 경로만 허용하고
                    // 명명된 하위 리소스는 허용하지 않음(위에 명시적으로
                    // 나열됨) — GET /api/devices/* 등이 향후 인증 규칙 아래로
                    // 자유롭게 떨어질 수 있게 하여 예기치 않은 허용 목록
                    // 충돌을 방지.
                    .requestMatchers(HttpMethod.PATCH, "/api/devices/*").permitAll()
                    // --- 인증 게이트: 광고 CRUD + 스케줄 변경 (AC 3) ------
                    // PUT/PATCH /api/ads/{id}/schedule은 관리자 UI가 호출하는
                    // 스케줄 mutator; JWT principal이 검증된 광고주 id를
                    // 운반하고, 서비스가 이를 사용해 소유권을 강제
                    // (AC 4 auth-and-isolation).
                    // `.anyRequest().authenticated()` 기본값에 비해 명시적
                    // 호출은 no-op이지만, 다른 관리자 라우트 옆에 계약을
                    // 검색 가능하게 유지함.
                    .requestMatchers("/api/ads/**").authenticated()
                    // --- 기본: 그 외에는 모두 유효한 JWT 필요 -------------
                    .anyRequest().authenticated()
            }
            .headers { headers ->
                // H2 콘솔은 프레임 내부에 렌더링; 개발 콘솔이 동작하도록
                // X-Frame-Options를 same-origin으로 완화.
                headers.frameOptions { fo -> fo.sameOrigin() }
            }
            // 두 보안 레이어 거부 경로에 대해 JSON을 발행하는 핸들러를
            // 연결하여 관리자 웹 + 플레이어 페이지가 거부의 출처와 무관하게
            // 단일 오류 계약을 보도록 함:
            //  - 보호된 엔드포인트에 토큰이 (없거나 유효하지 않게) 제시되면
            //    [JwtAuthenticationEntryPoint]를 통한 401 Unauthorized.
            //  - 인증된 principal이 필요한 권한을 갖지 않으면
            //    [JwtAccessDeniedHandler]를 통한 403 Forbidden.
            // 이것들이 없으면 스프링 시큐리티의 기본값은 HTML 오류 페이지나
            // `WWW-Authenticate: Basic` 챌린지를 발행 — JSON 전용 API
            // 클라이언트가 소비할 수 없음.
            .exceptionHandling { ex ->
                ex
                    .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                    .accessDeniedHandler(jwtAccessDeniedHandler)
            }
            // 인가 규칙이 평가되는 시점에 SecurityContext가 채워져 있도록
            // 스프링의 username/password 필터 전에 JWT 검증을 실행.
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}
