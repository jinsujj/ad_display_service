package me.owldev.adsignage.domain.advertiser

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 어드민 사이트의 RBAC 역할.
 *
 * - [ADVERTISER] : 광고주. 영상 업로드 / 광고 만들기 / 자기 광고가 어디
 *   송출 중인지 read-only 조회만.
 * - [OPERATOR]   : 플랫폼 운영자. 디바이스/음식점/큐 매칭을 직접 통제.
 *
 * 광고주가 임의로 디바이스 큐에 끼어들어 비즈니스 무결성을 깨지 못하도록
 * SecurityConfig 가 디바이스/큐 mutation 엔드포인트를 OPERATOR 로 게이트.
 */
enum class AdvertiserRole {
    ADVERTISER,
    OPERATOR,
}

/**
 * 광고주 계정.
 *
 * Flyway 마이그레이션 V1이 생성한 advertisers 테이블에 매핑.
 *
 * 표현된 온톨로지 개념:
 *  - advertiser_id        → [id]
 *  - advertiser_email     → [email] (유일)
 *  - advertiser_password_hash → [passwordHash] (bcrypt)
 */
@Entity
@Table(name = "advertisers")
class Advertiser(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),

    @Column(name = "email", nullable = false, unique = true, length = 255)
    var email: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    /**
     * RBAC 역할. 회원가입 시 default ADVERTISER. 운영자 승격은 DB SQL 또는
     * 향후 OPERATOR 가 다른 OPERATOR 를 임명하는 어드민 페이지에서.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    var role: AdvertiserRole = AdvertiserRole.ADVERTISER,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Advertiser) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
