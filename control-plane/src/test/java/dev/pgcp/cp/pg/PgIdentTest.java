package dev.pgcp.cp.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PgIdentTest {

    @Test
    void quotesEmbeddedQuotes() {
        assertThat(PgIdent.quote("app")).isEqualTo("\"app\"");
        assertThat(PgIdent.quote("we\"ird; drop table x")).isEqualTo("\"we\"\"ird; drop table x\"");
    }

    @Test
    void rejectsUnquotableIdentifiers() {
        assertThatThrownBy(() -> PgIdent.quote("")).hasMessageContaining("invalid");
        assertThatThrownBy(() -> PgIdent.quote("a\0b")).hasMessageContaining("invalid");
        assertThatThrownBy(() -> PgIdent.quote("x".repeat(64))).hasMessageContaining("invalid");
    }

    @Test
    void newNamesAreStrict() {
        assertThat(PgIdent.requireNewName("app_db", "database")).isEqualTo("app_db");
        for (String bad : new String[] { "App", "1db", "db-1", "db name", "pg_custom", "postgres", "public", "pgcp_admin",
                "a\"b" }) {
            assertThatThrownBy(() -> PgIdent.requireNewName(bad, "database")).as(bad).isNotNull();
        }
    }
}
