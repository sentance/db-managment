package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import dev.pgcp.agent.config.AgentException;
import tools.jackson.databind.json.JsonMapper;

class PgBackRestCatalogueParserTest {
    private final PgBackRestCatalogueParser parser = new PgBackRestCatalogueParser(JsonMapper.builder().build());
    private static final String PAYLOAD = """
            [{"name":"shared","status":{"code":0,"message":"do-not-expose-secret"},
              "repo":[{"key":1,"cipher":"none","status":{"code":0}}],
              "archive":[{"id":"16-1","min":"000000010000000000000001","max":"000000010000000000000003"}],
              "backup":[{"label":"20261108-000021F","type":"full","error":false,
                "timestamp":{"start":1794096021,"stop":1794096815},
                "info":{"size":1200,"repository":{"size":400}},
                "backrest":{"version":"2.57.0"},"secret":"do-not-expose-secret"}]}]
            """;

    @Test
    void readsExistingMetadataWithoutReturningRawMessagesOrUnknownFields() {
        var c = parser.parse(PAYLOAD, "shared");
        assertThat(c.stanza()).isEqualTo("shared");
        assertThat(c.repositories()).singleElement().satisfies(r -> assertThat(r.cipher()).isEqualTo("none"));
        assertThat(c.backups()).singleElement().satisfies(b -> {
            assertThat(b.databaseBytes()).isEqualTo(1200);
            assertThat(b.repositoryBytes()).isEqualTo(400);
            assertThat(b.finishedAt().getEpochSecond()).isEqualTo(1794096815);
            assertThat(b.error()).isFalse();
        });
        assertThat(c.toString()).doesNotContain("do-not-expose-secret");
    }

    @Test
    void acceptsEmptyRepositoriesAndFailedBackupIndicatorsWithoutInventingSuccess() {
        var empty = parser.parse("""
                [{"name":"shared","status":{"code":2},"repo":[{"key":1,"cipher":"none","status":{"code":0}}],"backup":[]}]
                """, "shared");
        assertThat(empty.statusCode()).isEqualTo(2);
        assertThat(empty.backups()).isEmpty();
        assertThat(parser.parse(PAYLOAD.replace("\"error\":false", "\"error\":true"), "shared").backups().getFirst().error()).isTrue();
        assertThat(parser.parse(PAYLOAD.replace("\"cipher\":\"none\"", "\"cipher\":\"aes-256-cbc\""), "shared")
                .repositories().getFirst().cipher()).isEqualTo("aes-256-cbc");
    }

    @Test
    void rejectsMalformedMismatchedOrIncompleteDataWithARedactedError() {
        for (String payload : new String[] { "secret=do-not-expose-secret", "{}", "[]",
                PAYLOAD.replace("\"name\":\"shared\"", "\"name\":\"other\""),
                PAYLOAD.replace("\"size\":1200", "\"size\":-1"),
                PAYLOAD.replace("\"code\":0", "\"code\":\"do-not-expose-secret\""),
                PAYLOAD.replace("\"stop\":1794096815", "\"stop\":1") }) {
            assertThatThrownBy(() -> parser.parse(payload, "shared"))
                    .isInstanceOf(AgentException.class).hasMessage("unrecognised pgBackRest catalogue format")
                    .hasNoCause();
        }
    }
}
