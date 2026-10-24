package dev.pgcp.agent.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.pgcp.agent.api.InstanceSpec;
import dev.pgcp.agent.config.AgentException;

class InstanceFilesTest {

    private static final UUID ID = UUID.fromString("7d1f6a3e-1111-4c4c-9d9d-000000000001");

    static InstanceSpec spec(String name, Map<String, String> settings, String bind, Integer port, List<String> nets) {
        return new InstanceSpec(ID, name, 18, 1024, 1.0, 256, settings, bind, port, nets,
                "Abcdefghijklmnopqrstuvwxyz012345", List.of());
    }

    @Test
    void rejectsUnsafeInstanceNames() {
        for (String bad : List.of("A", "1abc", "abc_def", "abc def", "abc;rm", "../x", "a".repeat(40))) {
            assertThatThrownBy(() -> InstanceFiles.validate(spec(bad, Map.of(), null, null, null)))
                    .isInstanceOf(AgentException.class);
        }
        InstanceFiles.validate(spec("app-db-1", Map.of(), null, null, null));
    }

    @Test
    void rejectsReservedOrUnsafeSettings() {
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of("archive_command", "true"), null, null, null)))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of("hba_file", "/tmp/x"), null, null, null)))
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of("work_mem", "4MB\"\n- evil"), null, null, null)))
                .hasMessageContaining("invalid value");
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of("work_mem", "${HOME}"), null, null, null)))
                .hasMessageContaining("invalid value");
        InstanceFiles.validate(spec("db", Map.of("work_mem", "8MB", "random_page_cost", "1.1"), null, null, null));
    }

    @Test
    void rejectsBadNetworkingInput() {
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of(), "0.0.0.0", null, null)))
                .hasMessageContaining("hostPort");
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of(), "localhost", 5433, null)))
                .hasMessageContaining("IPv4");
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of(), null, null, List.of("host"))))
                .hasMessageContaining("network");
        assertThatThrownBy(() -> InstanceFiles.validate(spec("db", Map.of(), null, null, List.of("a b"))))
                .hasMessageContaining("network");
    }

    @Test
    void publicExposureRequiresTlsInPgHba() {
        assertThat(InstanceFiles.pgHba(spec("db", Map.of(), "0.0.0.0", 5433, null)))
                .contains("hostssl all       all       0.0.0.0/0")
                .doesNotContain("host    all       all       0.0.0.0/0");
        assertThat(InstanceFiles.pgHba(spec("db", Map.of(), "127.0.0.1", 5433, null)))
                .contains("host    all       all       0.0.0.0/0");
        assertThat(InstanceFiles.pgHba(spec("db", Map.of(), null, null, null)))
                .doesNotContain("trust");
    }

    @Test
    void composeCarriesManagedLabelsLimitsAndCallerSettingsCannotOverrideTls() {
        String yaml = InstanceFiles.compose(spec("app", Map.of("work_mem", "8MB"), "127.0.0.1", 5433, List.of("coolify")),
                "pgcp-postgres:18-abc");
        assertThat(yaml)
                .contains("container_name: \"pgcp-inst-app\"")
                .contains("pgcp.managed: \"true\"")
                .contains("pgcp.instance-id: \"" + ID + "\"")
                .contains("mem_limit: \"1024m\"")
                .contains("\"127.0.0.1:5433:5432\"")
                .contains("- \"ssl=on\"")
                .contains("- \"work_mem=8MB\"")
                .contains("name: \"pgcp-inst-app-data\"")
                .contains("pgcp-postgres:18-abc")
                .contains("  coolify:\n    external: true")
                .contains("pgdata:/var/lib/postgresql\"")
                .doesNotContain("Abcdefghijklmnopqrstuvwxyz012345");
    }

    @Test
    void olderMajorsMountTheirDeclaredVolumePath() {
        var s = new InstanceSpec(ID, "old", 16, 512, 1, 128, Map.of(), null, null, null, "Abcdefghijklmnopqrstuvwxyz012345",
                List.of());
        assertThat(InstanceFiles.compose(s, "img")).contains("pgdata:/var/lib/postgresql/data\"");
    }

    @Test
    void specToStringNeverContainsPassword() {
        assertThat(spec("db", Map.of(), null, null, null).toString()).doesNotContain("Abcdefghijklmnopqrstuvwxyz012345");
    }
}
