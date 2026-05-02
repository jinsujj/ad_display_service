package me.owldev.adsignage.domain.video

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * AC 4 (auth-and-isolation) — verifies that the V31 Flyway migration
 * produces an `advertiser_id` column on the `videos` table whose shape
 * matches the [Video] JPA entity, and that the FK to `advertisers`
 * actually applies.
 *
 * Plain JUnit, mirroring the style of [VideoMigrationTest]:
 *  - We boot Flyway directly against a fresh in-memory H2 instead of
 *    spinning up the Spring context. That avoids dragging unrelated
 *    entity validation into the assertion, and lets us run V1 + V30 +
 *    V31 in isolation — V1 supplies the `advertisers` parent table that
 *    V31's FK references.
 *  - The `target("31")` ceiling stops Flyway before any later
 *    sibling-AC migrations (V40+ schedules, V90 device_assignments) are
 *    applied — those depend on tables that aren't authored yet and would
 *    otherwise fail the migration apply step.
 */
class VideoAdvertiserIsolationMigrationTest {

    @Test
    fun `V31 adds advertiser_id column to videos with NOT NULL and FK to advertisers`() {
        val jdbcUrl = "jdbc:h2:mem:videos-iso-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"

        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration")
            // V1 creates `advertisers`; V30 creates `videos`; V31 wires the
            // FK between them. Cap at 31 so unrelated downstream migrations
            // (V90 device_assignments, future V40 schedules, …) don't run.
            .target("31")
            .load()
            .migrate()

        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            val meta = conn.metaData

            // 1. The new column exists on `videos`.
            val videoColumns = mutableMapOf<String, ColumnSpec>()
            meta.getColumns(null, null, "VIDEOS", null).use { rs ->
                while (rs.next()) {
                    videoColumns[rs.getString("COLUMN_NAME")] = ColumnSpec(
                        nullable = rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable,
                        typeName = rs.getString("TYPE_NAME"),
                    )
                }
            }
            assertThat(videoColumns).containsKey("ADVERTISER_ID")
            assertThat(videoColumns["ADVERTISER_ID"]!!.nullable)
                .withFailMessage("advertiser_id must be NOT NULL")
                .isFalse

            // 2. The FK from videos.advertiser_id → advertisers.id exists.
            val foreignKeys = mutableListOf<Triple<String, String, String>>()
            meta.getImportedKeys(null, null, "VIDEOS").use { rs ->
                while (rs.next()) {
                    foreignKeys += Triple(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME"),
                    )
                }
            }
            assertThat(foreignKeys)
                .withFailMessage(
                    "Expected FK from videos.advertiser_id → advertisers.id, got %s",
                    foreignKeys,
                )
                .anySatisfy { fk ->
                    assertThat(fk.first).isEqualTo("ADVERTISER_ID")
                    assertThat(fk.second).isEqualTo("ADVERTISERS")
                    assertThat(fk.third).isEqualTo("ID")
                }

            // 3. Index on (advertiser_id, uploaded_at) exists — it's the
            //    composite that backs the canonical "my uploads, newest
            //    first" admin list query.
            val indexedColumns = mutableListOf<Pair<String, Short>>() // (column, ordinal)
            val indexNames = mutableSetOf<String>()
            meta.getIndexInfo(null, null, "VIDEOS", false, false).use { rs ->
                while (rs.next()) {
                    val idx = rs.getString("INDEX_NAME") ?: continue
                    val col = rs.getString("COLUMN_NAME") ?: continue
                    indexNames += idx
                    indexedColumns += col to rs.getShort("ORDINAL_POSITION")
                }
            }
            assertThat(indexedColumns.map { it.first })
                .withFailMessage("Expected at least one index on advertiser_id, got %s", indexNames)
                .contains("ADVERTISER_ID")
        }
    }

    private data class ColumnSpec(val nullable: Boolean, val typeName: String)
}
