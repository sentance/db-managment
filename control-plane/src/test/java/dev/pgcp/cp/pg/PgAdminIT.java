package dev.pgcp.cp.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the administrative SQL against real PostgreSQL versions with a non-superuser admin role, exactly as the
 * control plane uses it. PostgreSQL 16 changed CREATEROLE semantics, and 14 still has a permissive public schema.
 */
class PgAdminIT {

    @ParameterizedTest
    @ValueSource(strings = { "postgres:14-alpine", "postgres:16-alpine", "postgres:18-trixie" })
    void leastPrivilegeLifecycle(String image) throws Exception {
        try (var pg = new PostgreSQLContainer(image)) {
            pg.start();
            try (Connection su = connect(pg, "postgres", pg.getUsername(), pg.getPassword())) {
                PgAdmin.ensureAdminRole(su, "pgcp_admin", "AdminPassword123456789");
                // idempotent
                PgAdmin.ensureAdminRole(su, "pgcp_admin", "AdminPassword123456789");
            }
            try (Connection admin = connect(pg, "postgres", "pgcp_admin", "AdminPassword123456789")) {
                assertThat(PgAdmin.isSuperuser(admin)).isFalse();
                PgAdmin.ensureLoginRole(admin, "app_owner", "OwnerPassword123456789");
                PgAdmin.createDatabase(admin, "app", "app_owner");
                PgAdmin.createDatabase(admin, "app", "app_owner"); // idempotent
                PgAdmin.ensureLoginRole(admin, "app_ro", "ReaderPassword123456789");
                PgAdmin.ensureLoginRole(admin, "stranger", "StrangerPassword123456");
                if (PgAdmin.serverVersionNum(admin) < 150000) {
                    try (Connection suApp = connect(pg, "app", pg.getUsername(), pg.getPassword())) {
                        PgAdmin.securePublicSchemaPg14(suApp, "app_owner");
                    }
                }
            }

            try (Connection owner = connect(pg, "app", "app_owner", "OwnerPassword123456789")) {
                exec(owner, "create table items(id int primary key)");
                exec(owner, "insert into items values (1)");
            }
            try (Connection adminApp = connect(pg, "app", "pgcp_admin", "AdminPassword123456789")) {
                PgAdmin.grantAccess(adminApp, "app", "app_owner", "app_ro", PgAdmin.Access.READ_ONLY);
            }
            try (Connection owner = connect(pg, "app", "app_owner", "OwnerPassword123456789")) {
                exec(owner, "create table later(id int)"); // default privileges must cover future tables
            }

            try (Connection ro = connect(pg, "app", "app_ro", "ReaderPassword123456789")) {
                assertThat(count(ro, "select count(*) from items")).isEqualTo(1);
                assertThat(count(ro, "select count(*) from later")).isZero();
                assertThatThrownBy(() -> exec(ro, "insert into items values (2)")).hasMessageContaining("permission denied");
                assertThatThrownBy(() -> exec(ro, "create table nope(id int)")).hasMessageContaining("permission denied");
            }
            // PUBLIC lost CONNECT: an unrelated login role cannot even connect.
            assertThatThrownBy(() -> connect(pg, "app", "stranger", "StrangerPassword123456"))
                    .hasMessageContaining("permission denied");

            try (Connection admin = connect(pg, "postgres", "pgcp_admin", "AdminPassword123456789")) {
                PgAdmin.setPassword(admin, "app_ro", "RotatedPassword123456789");
                assertThat(PgAdmin.connectableDatabases(admin, "app_ro")).contains("app");
                List<PgAdmin.Dependency> deps = PgAdmin.dependencies(admin, "app_ro");
                assertThat(deps).isNotEmpty().allMatch(d -> !d.kind().equals("OWNER"));
                assertThat(PgAdmin.dependencies(admin, "app_owner")).anyMatch(d -> d.kind().equals("OWNER"));
            }
            assertThatThrownBy(() -> connect(pg, "app", "app_ro", "ReaderPassword123456789"))
                    .hasMessageContaining("password authentication failed");
            connect(pg, "app", "app_ro", "RotatedPassword123456789").close();

            try (Connection adminApp = connect(pg, "app", "pgcp_admin", "AdminPassword123456789")) {
                PgAdmin.revokeAccess(adminApp, "app", "app_owner", "app_ro");
            }
            assertThatThrownBy(() -> connect(pg, "app", "app_ro", "RotatedPassword123456789"))
                    .hasMessageContaining("permission denied");

            try (Connection admin = connect(pg, "postgres", "pgcp_admin", "AdminPassword123456789")) {
                for (String db : List.of("app", "postgres")) {
                    try (Connection inDb = connect(pg, db, "pgcp_admin", "AdminPassword123456789")) {
                        PgAdmin.dropOwnedPrivileges(inDb, "app_ro");
                    }
                }
                PgAdmin.dropRole(admin, "app_ro");
                assertThat(PgAdmin.roleExists(admin, "app_ro")).isFalse();

                PgAdmin.ensureLoginRole(admin, "we\"ird; drop role x", "WeirdPassword1234567890");
                assertThat(PgAdmin.roleExists(admin, "we\"ird; drop role x")).isTrue();
                PgAdmin.dropRole(admin, "we\"ird; drop role x");

                PgAdmin.renameDatabase(admin, "app", "app_renamed");
                assertThat(PgAdmin.databases(admin)).extracting(PgAdmin.DatabaseInfo::name).contains("app_renamed");
                PgAdmin.dropDatabase(admin, "app_renamed", false);
                assertThat(PgAdmin.databaseExists(admin, "app_renamed")).isFalse();
            }
        }
    }

    static Connection connect(PostgreSQLContainer pg, String db, String user, String password) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/" + db, user,
                password);
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static long count(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
