package dev.pgcp.agent.instances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.InstanceSpec;
import dev.pgcp.agent.api.LifecycleAction;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.CommandRunner;
import dev.pgcp.agent.docker.DockerGateway;
import dev.pgcp.agent.host.HostService;
import dev.pgcp.agent.ops.OperationService.Progress;
import dev.pgcp.agent.tls.InstanceCa;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real Docker daemon: run with ./gradlew :agent:test -PdockerTests */
class InstanceManagerDockerIT {

    private static final Progress NO_PROGRESS = (p, m) -> {
    };
    private static final String PASSWORD = "ItSuperuserPassword0123456789";

    @TempDir
    static Path dataDir;

    static DockerGateway docker;
    static CommandRunner commands;
    static InstanceManager manager;
    static InstanceCa ca;
    static String name = "it-" + Long.toString(System.nanoTime(), 36);
    static String foreign = "it-foreign-" + Long.toString(System.nanoTime(), 36);

    @BeforeAll
    static void setUp() {
        var props = new AgentProperties(dataDir, Path.of("../images/postgres").toAbsolutePath().normalize(), null, null,
                Map.of("data", dataDir), 0, 0, "docker", Duration.ofMinutes(3), null);
        docker = new DockerGateway();
        commands = new CommandRunner();
        ca = new InstanceCa(props);
        var host = new HostService(props, docker);
        manager = new InstanceManager(props, docker, commands, host, ca, new ImageService(props, docker, commands),
                JsonMapper.builder().build());
        if (!docker.networkExists(AgentApi.INTERNAL_NETWORK)) {
            commands.runChecked(List.of("docker", "network", "create", AgentApi.INTERNAL_NETWORK), null, Duration.ofSeconds(30));
        }
    }

    @AfterAll
    static void cleanUp() {
        for (String n : List.of(name, foreign)) {
            commands.run(List.of("docker", "rm", "-f", AgentApi.containerName(n)), null, Duration.ofMinutes(1));
            commands.run(List.of("docker", "volume", "rm", "-f", AgentApi.volumeName(n)), null, Duration.ofMinutes(1));
        }
    }

    @Test
    void fullLifecycleOfAManagedInstance() throws Exception {
        UUID id = UUID.randomUUID();
        int port = freePort();
        var spec = new InstanceSpec(id, name, 18, 512, 1.0, 128, Map.of("work_mem", "8MB"), "127.0.0.1", port, List.of(),
                PASSWORD, List.of());

        Map<String, String> result = manager.create(spec, NO_PROGRESS);
        assertThat(result).containsEntry("containerName", AgentApi.containerName(name));

        // The initdb password must not remain on disk after the first healthy start.
        assertThat(Files.readString(dataDir.resolve("instances").resolve(name).resolve("config/superuser-password")))
                .isEqualTo(InstanceFiles.PASSWORD_PLACEHOLDER);

        try (Connection c = connect(port, "require")) {
            c.createStatement().execute("create table survives_restart(id int primary key)");
            c.createStatement().execute("insert into survives_restart values (42)");
            try (ResultSet rs = c.createStatement().executeQuery("select ssl from pg_stat_ssl where pid = pg_backend_pid()")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("connection uses TLS").isTrue();
            }
            try (ResultSet rs = c.createStatement().executeQuery("show work_mem")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("8MB");
            }
        }

        // Certificate chains to the instance CA and names localhost.
        Path caFile = dataDir.resolve("client-ca.crt");
        Files.writeString(caFile, ca.caCertificatePem());
        Properties verify = new Properties();
        verify.setProperty("sslrootcert", caFile.toString());
        try (Connection c = connect("localhost", port, "verify-full", verify)) {
            assertThat(c.isValid(5)).isTrue();
        }

        manager.lifecycle(name, id, LifecycleAction.RESTART, NO_PROGRESS);
        try (Connection c = connect(port, "require");
                ResultSet rs = c.createStatement().executeQuery("select id from survives_restart")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(42);
        }

        // Wrong instance id must never be able to act on the container.
        assertThatThrownBy(() -> manager.lifecycle(name, UUID.randomUUID(), LifecycleAction.STOP, NO_PROGRESS))
                .isInstanceOf(AgentException.class).extracting(e -> ((AgentException) e).kind())
                .isEqualTo(AgentException.Kind.FORBIDDEN);

        manager.delete(name, id, true, NO_PROGRESS);
        assertThat(docker.inspect(AgentApi.containerName(name))).isEmpty();
        assertThat(commands.run(List.of("docker", "volume", "inspect", AgentApi.volumeName(name)), null,
                Duration.ofSeconds(30)).exitCode()).isNotZero();
    }

    @Test
    void refusesToTakeOverOrOperateForeignContainers() {
        commands.runChecked(List.of("docker", "create", "--name", AgentApi.containerName(foreign), "postgres:18-trixie"), null,
                Duration.ofMinutes(2));
        var spec = new InstanceSpec(UUID.randomUUID(), foreign, 18, 512, 1.0, 128, Map.of(), null, null, List.of(), PASSWORD,
                List.of());
        assertThatThrownBy(() -> manager.create(spec, NO_PROGRESS)).isInstanceOf(AgentException.class)
                .hasMessageContaining("not this instance");
        assertThatThrownBy(() -> manager.lifecycle(foreign, spec.instanceId(), LifecycleAction.STOP, NO_PROGRESS))
                .isInstanceOf(AgentException.class).extracting(e -> ((AgentException) e).kind())
                .isEqualTo(AgentException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> docker.execFixed(AgentApi.containerName(foreign), List.of("true")))
                .isInstanceOf(AgentException.class).hasMessageContaining("not managed");
        assertThat(docker.inspect(AgentApi.containerName(foreign))).get().extracting(i -> i.state()).isEqualTo("created");
    }

    private static Connection connect(int port, String sslmode) throws Exception {
        return connect("127.0.0.1", port, sslmode, new Properties());
    }

    private static Connection connect(String host, int port, String sslmode, Properties extra) throws Exception {
        Properties p = new Properties();
        p.putAll(extra);
        p.setProperty("user", "postgres");
        p.setProperty("password", PASSWORD);
        p.setProperty("sslmode", sslmode);
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/postgres", p);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
