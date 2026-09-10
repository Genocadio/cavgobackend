package com.nexxserve.cavgomain;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@code sql/relax_user_names_not_null.sql} against the legacy schema.
 *
 * <p>Production {@code users.first_name}/{@code users.last_name} were created
 * NOT NULL by an older entity (nullable = false) and {@code ddl-auto: update}
 * never removes constraints, so Nexxauth single-name profiles fail the
 * inline-sync INSERT with SQLState 23502. The startup script drops those NOT
 * NULLs; this test proves it turns the failing INSERT into a succeeding one.
 */
class RelaxUserNamesNotNullSqlTest {

    /** Mirrors the legacy Hibernate-generated users table (names NOT NULL). */
    private static final String LEGACY_DDL = """
            CREATE TABLE users (
                id            BIGINT PRIMARY KEY,
                created_at    TIMESTAMP NOT NULL,
                updated_at    TIMESTAMP,
                created_by    VARCHAR(255),
                updated_by    VARCHAR(255),
                first_name    VARCHAR(255) NOT NULL,
                last_name     VARCHAR(255) NOT NULL,
                email         VARCHAR(255),
                phone         VARCHAR(255),
                status        VARCHAR(255),
                data_hash     VARCHAR(255),
                date_of_birth DATE,
                address       VARCHAR(255),
                version       BIGINT
            )
            """;

    @Test
    void script_dropsNotNullOnNameColumns_soSingleNameProfilesCanBeProvisioned() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:relaxusernames;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            try (Statement st = conn.createStatement()) {
                st.execute(LEGACY_DDL);
            }

            // Legacy schema: a Nexxauth single-name profile (null last_name) is rejected.
            assertThatThrownBy(() -> insertUser(conn, 1L, "Hajyengimana", null))
                    .as("legacy NOT NULL must reject null last_name")
                    .isInstanceOf(SQLException.class);

            // Run the exact script the app executes at startup.
            try (InputStream in = getClass().getResourceAsStream("/sql/relax_user_names_not_null.sql")) {
                assertThat(in).as("sql/relax_user_names_not_null.sql must be on the classpath").isNotNull();
                runScript(conn, in);
            }

            // Same insert now succeeds with a null last_name...
            insertUser(conn, 1L, "Hajyengimana", null);
            assertThat(lastNameOf(conn, 1L)).isNull();

            // ...and named profiles are unaffected.
            insertUser(conn, 2L, "John", "Doe");
            assertThat(lastNameOf(conn, 2L)).isEqualTo("Doe");

            // The script is idempotent — re-running (every app boot) is a no-op.
            try (InputStream in = getClass().getResourceAsStream("/sql/relax_user_names_not_null.sql")) {
                runScript(conn, in);
            }
            insertUser(conn, 3L, "Single", null);
            assertThat(lastNameOf(conn, 3L)).isNull();
        }
    }

    private static void runScript(Connection conn, InputStream in) throws Exception {
        ScriptUtils.executeSqlScript(conn,
                new EncodedResource(new ByteArrayResource(in.readAllBytes()), StandardCharsets.UTF_8));
    }

    private static void insertUser(Connection conn, long id, String firstName, String lastName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, created_at, first_name, last_name, version) VALUES (?, NOW(), ?, ?, 0)")) {
            ps.setLong(1, id);
            ps.setString(2, firstName);
            ps.setString(3, lastName);
            ps.executeUpdate();
        }
    }

    private static String lastNameOf(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_name FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
