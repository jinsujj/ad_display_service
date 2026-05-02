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
 * Sub-AC 2 of AC 302 — Spring Security wiring.
 *
 * Defines the single [SecurityFilterChain] for the API:
 *
 *  - **Stateless** session policy. The API is JWT-bearer driven; we never
 *    create or rely on `HttpSession`. This also disables Spring's default
 *    SESSION cookie, which would otherwise confuse the SSE long-poll player
 *    behind nginx.
 *  - **CSRF disabled.** CSRF only protects cookie-borne credentials; we use
 *    `Authorization: Bearer …` exclusively, and the player page uses no
 *    cookies for auth. Disabling CSRF also unblocks the JSON `POST/PUT`
 *    contract used by the admin web client.
 *  - **HTTP Basic / form login disabled.** They would inject default
 *    `WWW-Authenticate: Basic` realms onto 401s and add a /login form route
 *    we don't want.
 *  - **Public endpoints** (no token required):
 *      - `POST /api/auth/{...}` — signup + login (issue the token)
 *      - `GET  /actuator/health` — uptime probes for nginx / load balancer
 *      - `GET  /h2-console/{...}` — dev-only DB browser (also: frame options
 *        relaxed to `sameOrigin` so the H2 web UI's frames load)
 *      - Player APIs — the Next.js `/player/{deviceId}` page calls the
 *        backend without a JWT (the device is unauthenticated; the
 *        deviceId path param is the bearer of identity for the hackathon).
 *        SSE stream + per-device playlist + the streaming child path
 *        `GET /api/videos/{filename}` are exposed; the parent
 *        `GET /api/videos` collection (admin list) is *not* in the
 *        allow-list so AC 4's per-advertiser ownership filter cannot be
 *        bypassed by an anonymous request.
 *  - **All other routes** require an authenticated principal — populated
 *    upstream by [JwtAuthenticationFilter] when a valid bearer token is
 *    presented. Endpoints that have not yet been migrated to the
 *    authenticated contract (e.g. the device-assignment admin routes —
 *    see [me.owldev.adsignage.domain.assignment.DeviceAssignmentController])
 *    are explicitly allow-listed here as a transitional measure; they will
 *    be locked down in the auth-and-isolation pass.
 *
 * **Filter order.** [JwtAuthenticationFilter] is registered *before*
 * [UsernamePasswordAuthenticationFilter] so that:
 *  1. A successful JWT validation populates the SecurityContext.
 *  2. The downstream `authorizeHttpRequests` rules can then evaluate
 *     `.authenticated()` against the populated context.
 *  3. Spring's default `UsernamePasswordAuthenticationFilter` (which would
 *     otherwise look for `username`/`password` form params on `/login`)
 *     never wins precedence — its place in the chain is preserved only as
 *     the conventional anchor point for our JWT filter.
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
                    // --- Public: auth (login/signup issues the JWT) --------
                    .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                    // --- Public: health probe ------------------------------
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    // --- Public: dev-only H2 console -----------------------
                    .requestMatchers("/h2-console/**").permitAll()
                    // --- Public: player APIs (device-side, no JWT) ---------
                    // SSE stream the Next.js player subscribes to.
                    // `/events` — original AC 5 SSE wire (DeviceSseController).
                    // `/stream` — sub-AC 50002 SSE wire (DeviceStreamController);
                    //            kept as a sibling carve-out so both routes
                    //            remain reachable to anonymous device callers.
                    .requestMatchers(HttpMethod.GET, "/api/devices/*/events").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/devices/*/stream").permitAll()
                    // Playlist + video range endpoints land here in later
                    // sub-ACs; pre-permitting their prefix keeps the player
                    // page deployable as soon as those endpoints exist.
                    .requestMatchers(HttpMethod.GET, "/api/devices/*/playlist").permitAll()
                    // AC 20202 Sub-AC 2: POST /api/devices/{id}/play-events
                    // — the player reports "ad started" / "ad finished" here
                    // so the backend can update server-side play counts.
                    // Same anonymous-device contract as the sibling
                    // `…/stream` / `…/playlist` routes; the deviceId path
                    // parameter is the bearer of identity until the auth-
                    // and-isolation pass introduces a device enrolment
                    // token.
                    .requestMatchers(HttpMethod.POST, "/api/devices/*/play-events").permitAll()
                    // AC 4 (auth-and-isolation): the streaming endpoint
                    // `GET /api/videos/{filename}` stays public so the
                    // player page can fetch MP4 bytes without a JWT, but
                    // the *parent* `GET /api/videos` (admin list) must be
                    // authenticated so it can be ownership-filtered. Using
                    // `/api/videos/*` rather than `/api/videos/**` matches
                    // exactly one trailing path segment, leaving the
                    // collection URI to fall through to `.authenticated()`.
                    .requestMatchers(HttpMethod.GET, "/api/videos/*").permitAll()
                    // --- Transitional: admin endpoints not yet auth-gated.
                    // These will require ROLE_ADVERTISER once the auth-and-
                    // isolation pass lands. Kept open here so the green test
                    // suite stays green while we wire the JWT filter into the
                    // chain.
                    .requestMatchers("/api/devices/*/assignment").permitAll()
                    // Sub-AC 50101.1: PATCH /api/devices/{deviceId}/restaurant
                    // — admin remap entry point for demo scenario #3.
                    // Permitted alongside the sibling `…/assignment` route as
                    // a transitional carve-out; locks down in the auth-and-
                    // isolation pass that gates `/api/devices/**` admin CRUD
                    // behind ROLE_ADVERTISER.
                    .requestMatchers("/api/devices/*/restaurant").permitAll()
                    // AC 9, Sub-AC 1: PATCH /api/devices/{deviceId} — generic
                    // partial-update for device fields (restaurantId today;
                    // screen/group when V10 devices table grows the columns).
                    // Single-segment matcher so we permit only the device-root
                    // path, not its named subresources (those are listed
                    // explicitly above) — keeps GET /api/devices/* etc. free
                    // to land under a future authenticated rule without a
                    // surprise allow-list collision.
                    .requestMatchers(HttpMethod.PATCH, "/api/devices/*").permitAll()
                    // --- Auth-gated: ad CRUD + schedule mutation (AC 3) ----
                    // PUT/PATCH /api/ads/{id}/schedule is the schedule
                    // mutator the admin UI calls; the JWT principal carries
                    // the verified advertiser id, which the service uses
                    // to enforce ownership (AC 4 auth-and-isolation).
                    // Calling it out explicitly here is a no-op against the
                    // `.anyRequest().authenticated()` default, but keeps the
                    // contract searchable next to the other admin routes.
                    .requestMatchers("/api/ads/**").authenticated()
                    // --- Default: anything else needs a valid JWT ----------
                    .anyRequest().authenticated()
            }
            .headers { headers ->
                // H2 console renders inside frames; relax X-Frame-Options
                // for same-origin so the dev console works.
                headers.frameOptions { fo -> fo.sameOrigin() }
            }
            // Wire JSON-emitting handlers for the two security-layer
            // rejection paths so the admin web + player page see a single
            // error contract regardless of where the rejection originates:
            //  - 401 Unauthorized via [JwtAuthenticationEntryPoint] when no
            //    (or an invalid) token is presented to a protected endpoint.
            //  - 403 Forbidden via [JwtAccessDeniedHandler] when an
            //    authenticated principal lacks the required authority.
            // Without these, Spring Security's defaults would emit either
            // an HTML error page or a `WWW-Authenticate: Basic` challenge
            // — neither consumable by the JSON-only API clients.
            .exceptionHandling { ex ->
                ex
                    .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                    .accessDeniedHandler(jwtAccessDeniedHandler)
            }
            // Run JWT validation before Spring's username/password filter so
            // the SecurityContext is populated by the time authorization
            // rules are evaluated.
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}
