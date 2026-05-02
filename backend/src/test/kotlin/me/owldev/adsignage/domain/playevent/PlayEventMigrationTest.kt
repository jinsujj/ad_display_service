package me.owldev.adsignage.domain.playevent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * AC 20202 Sub-AC 2 — verifies that the V100 Flyway migration creates a
 * `play_events` table whose columns match the [PlayEvent] entity and whose
 * `event_type` CHECK constraint rejects unknown discriminators.
 *
 * Plain JUnit + JDBC. We deliberately do NOT run the migration through
 * Flyway here, for the same reason `AdMigrationTest` uses `target("40")`:
 * V90 (device_assignments) FKs to a `devices` parent table that is not
 * authored under its own migration yet, so a fresh H2 cannot satisfy V90's
 * dependencies. Flyway's `baselineVersion("99")` documentation suggests
 * that calling `.baseline()` should let us skip V1..V99 and run only V100,
 * but in practice Flyway 9.x re-evaluates the classpath migrations
 * regardless of the baseline marker and still attempts V90.
 *
 * Sidestepping Flyway is fine: V100 is pure DDL with no Flyway-specific
 * placeholders, no callbacks, no `${} ` substitutions. The test reads the
 * file off the classpath and executes it as a single SQL statement via
 * JDBC — exactly what Flyway would do, minus the dependency on the rest
 * of the migration history. The test still pins behaviour to the bytes
 * shipped on disk.
 *
 * Sync target: `src/main/resources/db/migration/V100__create_play_events.sql`.
 */
class PlayEventMigrationTest {

    @Test
    fun `V100 creates play_events table with the columns the entity expects`() {
        freshH2().use { conn ->
            applyV100(conn)
            val meta = conn.metaData

            // Table exists.
            meta.getTables(null, null, "PLAY_EVENTS", arrayOf("TABLE")).use { rs ->
                assertThat(rs.next())
                    .withFailMessage("Expected V100 to create a `play_events` table")
                    .isTrue
            }

            // Columns match the entity contract from PlayEvent.kt.
            val expected = setOf(
                "ID",
                "DEVICE_ID",
                "AD_ID",
                "EVENT_TYPE",
                "OCCURRED_AT",
                "RECEIVED_AT",
            )
            val actual = mutableSetOf<String>()
            meta.getColumns(null, null, "PLAY_EVENTS", null).use { rs ->
                while (rs.next()) actual.add(rs.getString("COLUMN_NAME"))
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)
        }
    }

    @Test
    fun `V100 rejects unknown event_type via CHECK constraint`() {
        freshH2().use { conn ->
            applyV100(conn)
            val ex = runCatching {
                conn.prepareStatement(
                    """
                    INSERT INTO play_events (id, device_id, ad_id, event_type, occurred_at)
                    VALUES ('pe-1', 'dev-1', 'ad-1', 'PAUSED', CURRENT_TIMESTAMP)
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }.exceptionOrNull()

            assertThat(ex)
                .withFailMessage("Expected ck_play_events_type to reject 'PAUSED'")
                .isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `V100 accepts STARTED and FINISHED event types`() {
        freshH2().use { conn ->
            applyV100(conn)
            // Both legitimate values must succeed — guards against an
            // overly-narrow CHECK that accidentally rejects one variant.
            conn.prepareStatement(
                """
                INSERT INTO play_events (id, device_id, ad_id, event_type, occurred_at)
                VALUES ('pe-2', 'dev-1', 'ad-1', 'STARTED', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { it.executeUpdate() }

            conn.prepareStatement(
                """
                INSERT INTO play_events (id, device_id, ad_id, event_type, occurred_at)
                VALUES ('pe-3', 'dev-1', 'ad-1', 'FINISHED', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { it.executeUpdate() }

            conn.prepareStatement("SELECT COUNT(*) FROM play_events").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(2)
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Open a fresh in-memory H2 instance under PostgreSQL compatibility
     * mode (matches production Postgres semantics for CHECK / TIME / TIMESTAMP).
     * Each test gets its own database via a UUID suffix so they do not
     * leak state between runs.
     */
    private fun freshH2(): Connection {
        val jdbcUrl =
            "jdbc:h2:mem:play-events-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        return DriverManager.getConnection(jdbcUrl, "sa", "")
    }

    /**
     * Apply the V100 migration to [conn] by reading the on-disk SQL and
     * splitting it into statements. The file ships as four DDL statements
     * (CREATE TABLE + two CREATE INDEX) separated by `;`. Comment lines
     * starting with `--` are stripped so the splitter does not split on
     * a `;` inside a comment (none today, but future-proofing).
     */
    private fun applyV100(conn: Connection) {
        val sql = readClasspath("/db/migration/V100__create_play_events.sql")
        val cleaned = sql
            .lineSequence()
            .filter { !it.trimStart().startsWith("--") }
            .joinToString("\n")
        for (raw in cleaned.split(';')) {
            val stmt = raw.trim()
            if (stmt.isEmpty()) continue
            conn.prepareStatement(stmt).use { it.executeUpdate() }
        }
    }

    private fun readClasspath(path: String): String {
        val resource = checkNotNull(this::class.java.getResource(path)) {
            "Resource $path not found on classpath"
        }
        return resource.readText(Charsets.UTF_8)
    }
}
