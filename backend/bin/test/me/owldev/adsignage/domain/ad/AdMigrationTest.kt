package me.owldev.adsignage.domain.ad

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Verifies that the V40 Flyway migration produces an `ads` table whose shape
 * matches the [Ad] JPA entity exactly, that the schedule fields carry the
 * intended CHECK constraints, and that FKs back to `advertisers` and
 * `videos` are wired.
 *
 * Plain JUnit on purpose — same rationale as `VideoMigrationTest`: avoid
 * `@DataJpaTest` so this Sub-AC's verification doesn't transitively demand
 * tables this slice doesn't own (e.g. `devices`, `restaurants`). We apply
 * V1 (advertisers) and V30 (videos) as parents, then V40 (ads), and assert
 * directly against JDBC metadata.
 *
 * Sync target: `src/main/resources/db/migration/V40__create_ads.sql`.
 */
class AdMigrationTest {

    @Test
    fun `V40 creates ads table with the columns the entity expects`() {
        val jdbcUrl = "jdbc:h2:mem:ads-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration")
            // Apply V1 (advertisers) + V30 (videos) as parents, then V40.
            // Stop at 40 so unrelated migrations (V90 device_assignments,
            // which depends on tables not authored under their own migrations
            // yet) don't fail this slice.
            .target("40")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            val meta = conn.metaData

            // Table exists.
            meta.getTables(null, null, "ADS", arrayOf("TABLE")).use { rs ->
                assertThat(rs.next())
                    .withFailMessage("Expected V40 to create an `ads` table")
                    .isTrue
            }

            // Columns match the entity contract from Ad.kt.
            val expected = setOf(
                "ID",
                "ADVERTISER_ID",
                "TITLE",
                "VIDEO_FILENAME",
                "START_TIME",
                "END_TIME",
                "DAILY_PLAY_COUNT",
                "CREATED_AT",
            )
            val actual = mutableSetOf<String>()
            meta.getColumns(null, null, "ADS", null).use { rs ->
                while (rs.next()) actual.add(rs.getString("COLUMN_NAME"))
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)

            // FK to advertisers.id — playlist + admin scoping rely on this.
            val importedFkTables: MutableSet<String> = mutableSetOf()
            meta.getImportedKeys(null, null, "ADS").use { rs ->
                while (rs.next()) {
                    val pk: String? = rs.getString("PKTABLE_NAME")
                    if (pk != null) importedFkTables.add(pk)
                }
            }
            assertThat(importedFkTables.toList())
                .withFailMessage("Expected `ads` to FK to both `advertisers` and `videos` (got %s)", importedFkTables)
                .containsAll(listOf("ADVERTISERS", "VIDEOS"))
        }
    }

    @Test
    fun `V40 enforces end_time greater than start_time via CHECK constraint`() {
        val jdbcUrl = "jdbc:h2:mem:ads-ck-window-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration")
            .target("40")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            // Seed FK parents — minimal advertiser + video so the FK doesn't
            // trip first and mask the CHECK constraint we're actually testing.
            conn.prepareStatement(
                """
                INSERT INTO advertisers (id, email, password_hash)
                VALUES ('adv-1', 'a@b.com', 'h')
                """.trimIndent(),
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                """
                INSERT INTO videos (id, filename, original_name, mime_type, size_bytes, storage_path, advertiser_id)
                VALUES ('vid-1', 'v.mp4', 'v.mp4', 'video/mp4', 1, '/tmp/v.mp4', 'adv-1')
                """.trimIndent(),
            ).use { it.executeUpdate() }

            // start == end should violate ck_ads_time_window (strict >).
            val ex = runCatching {
                conn.prepareStatement(
                    """
                    INSERT INTO ads (id, advertiser_id, title, video_filename,
                                     start_time, end_time, daily_play_count)
                    VALUES ('ad-1', 'adv-1', 't', 'v.mp4', '09:00:00', '09:00:00', 10)
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }.exceptionOrNull()

            assertThat(ex)
                .withFailMessage("Expected the time-window CHECK to reject end_time == start_time")
                .isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `V40 enforces positive daily_play_count via CHECK constraint`() {
        val jdbcUrl = "jdbc:h2:mem:ads-ck-count-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration")
            .target("40")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()

        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO advertisers (id, email, password_hash)
                VALUES ('adv-2', 'c@d.com', 'h')
                """.trimIndent(),
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                """
                INSERT INTO videos (id, filename, original_name, mime_type, size_bytes, storage_path, advertiser_id)
                VALUES ('vid-2', 'w.mp4', 'w.mp4', 'video/mp4', 1, '/tmp/w.mp4', 'adv-2')
                """.trimIndent(),
            ).use { it.executeUpdate() }

            val ex = runCatching {
                conn.prepareStatement(
                    """
                    INSERT INTO ads (id, advertiser_id, title, video_filename,
                                     start_time, end_time, daily_play_count)
                    VALUES ('ad-2', 'adv-2', 't', 'w.mp4', '09:00:00', '17:00:00', 0)
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }.exceptionOrNull()

            assertThat(ex)
                .withFailMessage("Expected the daily_play_count CHECK to reject 0")
                .isInstanceOf(SQLException::class.java)
        }
    }
}
