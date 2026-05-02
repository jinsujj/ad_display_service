package me.owldev.adsignage.domain.advertiser

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

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
