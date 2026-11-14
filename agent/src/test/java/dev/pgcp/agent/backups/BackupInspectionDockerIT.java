package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.pgcp.agent.api.BackupInspectionRequest;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.docker.CommandRunner;
import dev.pgcp.agent.docker.DockerGateway;
import tools.jackson.databind.json.JsonMapper;

class BackupInspectionDockerIT {
    private final CommandRunner commands = new CommandRunner();
    private final String project = "pgcp-fixture-inspect-" + Long.toString(System.nanoTime(), 36);

    @Test
    void inspectsAnExternalSourceWithoutChangingItsConfigurationOrData() throws Exception {
        try (var docker = new DockerGateway()) {
            try {
                fixture("up");
                String id = fixture("container").strip();
                var source = docker.inspect(id).orElseThrow();
                assertThat(source.managed()).isFalse();
                String before = checksum(id);
                var parser = new PgBackRestCatalogueParser(JsonMapper.builder().build());
                var req = new BackupInspectionRequest(source.name(), source.id(), "fixture");
                var empty = parser.parse(docker.pgBackRestInfo(req), "fixture");
                assertThat(empty.backups()).isEmpty();
                fixture("backup");
                var catalogue = parser.parse(docker.pgBackRestInfo(req), "fixture");
                assertThat(catalogue.statusCode()).isZero();
                assertThat(catalogue.backups()).hasSize(1);
                assertThat(checksum(id)).isEqualTo(before);
                assertThat(commands.runChecked(List.of("docker", "exec", "--user", "postgres", id, "psql", "-X",
                        "-d", "fixture", "-Atc", "select count(*) from orders"), null, Duration.ofSeconds(20)).strip()).isEqualTo("3");
                assertThatThrownBy(() -> docker.execFixed(source.name(), List.of("true")))
                        .isInstanceOf(AgentException.class).hasMessageContaining("not managed");
                assertThatThrownBy(() -> docker.pgBackRestInfo(new BackupInspectionRequest(source.name(), "0".repeat(64), "fixture")))
                        .isInstanceOf(AgentException.class).hasMessageContaining("replaced");
                assertThatThrownBy(() -> docker.pgBackRestInfo(new BackupInspectionRequest(source.name(), source.id(), "fixture --config=/tmp/x")))
                        .isInstanceOf(AgentException.class).hasMessageContaining("valid container");
            } finally { fixture("down"); }
        }
    }

    private String checksum(String id) {
        return commands.runChecked(List.of("docker", "exec", id, "sha256sum", "/etc/pgbackrest/pgbackrest.conf"),
                null, Duration.ofSeconds(20));
    }

    private String fixture(String action) {
        return commands.runChecked(List.of("env", "PGCP_FIXTURE_PROJECT=" + project, "bash", "deploy/backup-fixture.sh", action),
                Path.of("..").toAbsolutePath().normalize(), Duration.ofMinutes(8));
    }
}
