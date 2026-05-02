package me.owldev.adsignage.domain.video

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Verifies that the V30 Flyway migration produces a `videos` table whose
 * shape matches the [Video] JPA entity exactly.
 *
 * Plain JUnit on purpose. We deliberately do not boot the Spring context
 * here:
 *
 *  - `@DataJpaTest` would force `hibernate.ddl-auto=validate` to scan every
 *    `@Entity` in the module (Advertiser, DeviceAssignment, …) and demand
 *    those tables exist too — coupling this Sub-AC's verification to the
 *    state of unrelated migrations (some of which, like V90, depend on
 *    parent tables that aren't yet authored under their own migration).
 *  - This narrower test exercises Flyway directly against a fresh in-memory
 *    H2, applying V30 in isolation. If the SQL is malformed, the migration
 *    fails. If a column drifts from the entity contract, the JDBC metadata
 *    assertions fail.
 *
 * Sync target: `src/main/resources/db/migration/V30__create_videos.sql`.
 */
class VideoMigrationTest {

    @Test
    fun `V30 creates videos table with the columns the entity expects`() {
        val jdbcUrl = "jdbc:h2:mem:videos-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration")
            // Apply only V30 (and any later video-only migrations); skip V1
            // advertisers / V90 device_assignments which are unrelated to the
            // videos slice and may depend on tables this test doesn't create.
            .target("30")
            .baselineOnMigrate(true)
            .baselineVersion("29")
            .load()
            .migrate()

        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            val meta = conn.metaData

            // Table exists.
            meta.getTables(null, null, "VIDEOS", arrayOf("TABLE")).use { rs ->
                assertThat(rs.next())
                    .withFailMessage("Expected V30 to create a `videos` table")
                    .isTrue
            }

            // Columns match the entity contract from Video.kt.
            val expected = setOf(
                "ID",
                "FILENAME",
                "ORIGINAL_NAME",
                "MIME_TYPE",
                "SIZE_BYTES",
                "STORAGE_PATH",
                "UPLOADED_AT",
            )
            val actual = mutableSetOf<String>()
            meta.getColumns(null, null, "VIDEOS", null).use { rs ->
                while (rs.next()) actual.add(rs.getString("COLUMN_NAME"))
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)

            // Filename uniqueness: hot path for the streaming endpoint.
            val uniqueColumns = mutableSetOf<String>()
            meta.getIndexInfo(null, null, "VIDEOS", true, false).use { rs ->
                while (rs.next()) {
                    rs.getString("COLUMN_NAME")?.let(uniqueColumns::add)
                }
            }
            assertThat(uniqueColumns).contains("FILENAME")
        }
    }
}
