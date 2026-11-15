package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.CommandRunner;
import dev.pgcp.agent.docker.DockerGateway;
import dev.pgcp.agent.host.HostService;
import dev.pgcp.agent.instances.ImageService;
import dev.pgcp.agent.instances.InstanceManager;
import dev.pgcp.agent.ops.OperationService;
import dev.pgcp.agent.tls.InstanceCa;
import tools.jackson.databind.json.JsonMapper;

class LogicalBackupDockerIT {
    @TempDir Path data;
    private final CommandRunner commands = new CommandRunner();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String project = "pgcp-fixture-logical-" + Long.toString(System.nanoTime(), 36);
    private int pgMajor;

    @ParameterizedTest
    @ValueSource(ints={16,18})
    void encryptsUploadsRestoresAndVerifiesAConsistentSnapshotWithRestartReconciliation(int major) throws Exception {
        pgMajor=major;
        LogicalWorkerService workers = null;
        OperationService locks = null;
        InstanceManager instances = null;
        UUID restoredInstance = UUID.randomUUID();
        String name = "it-restore-" + Long.toString(System.nanoTime(), 36);
        try (var docker = new DockerGateway()) {
            try {
                compose("up", "-d", "--wait", "--wait-timeout", "90");
                String sourceId = compose("ps", "-q", "source").trim();
                var source = docker.inspect(sourceId).orElseThrow();
                String cp = docker.inspect(compose("ps", "-q", "control-plane").trim()).orElseThrow().name();
                String network = project + "_default";
                var props = new AgentProperties(data, Path.of("../images/postgres").toAbsolutePath().normalize(), null, null,
                        Map.of("data", data), 0, 0, "docker", Duration.ofMinutes(3), null);
                locks = new OperationService(props, json);
                workers = new LogicalWorkerService(props, docker, commands, locks, json, true, network);
                var key = workers.recoveryKey();
                assertThat(key.toString()).doesNotContain(key.identity().trim());
                String image = "pgcp-backup-worker:18-" + ImageService.hashContext(Path.of("../images/backup-worker"));
                run(List.of("docker", "run", "--rm", "--network", network, "-e", "AWS_ACCESS_KEY_ID=test",
                        "-e", "AWS_SECRET_ACCESS_KEY=test", "-e", "AWS_DEFAULT_REGION=us-east-1", "--entrypoint", "aws", image,
                        "--endpoint-url", "http://storage:5000", "s3api", "create-bucket", "--bucket", "pgcp-test"));
                var storage = new LogicalBackupRequest.Storage("http://storage:5000", "us-east-1", "pgcp-test",
                        "pgcp/fixture", "test", "test", key.recipient(), key.identity());
                UUID testId = UUID.randomUUID();
                await(workers, workers.start(new LogicalBackupRequest(testId, LogicalBackupRequest.Action.TEST_STORAGE,
                        testId, null, null, 18, null, storage, null, null, null, null)));
                UUID failureId=UUID.randomUUID();
                var missingBucket=new LogicalBackupRequest.Storage(storage.endpoint(),storage.region(),"missing-bucket",storage.prefix(),
                        storage.accessKey(),storage.secretKey(),storage.recipient(),null);
                var failed=terminal(workers,workers.start(new LogicalBackupRequest(failureId,LogicalBackupRequest.Action.TEST_STORAGE,
                        failureId,null,null,18,null,missingBucket,null,null,null,null)));
                assertThat(failed.status()).isEqualTo(OperationStatus.FAILED);
                assertThat(failed.result()).isEmpty();
                assertThat(Files.exists(data.resolve("logical-operations/"+failureId+"/request.json"))).isFalse();
                UUID backupId = UUID.randomUUID();
                var connection = new LogicalBackupRequest.Connection(source.name(), 5432, "fixture", "postgres", "test-password", "disable", null);
                var request = new LogicalBackupRequest(backupId, LogicalBackupRequest.Action.BACKUP, UUID.randomUUID(),
                        source.name(), source.id(), pgMajor, connection, storage,
                        "pgcp/fixture/" + backupId + ".dump.age", "pgcp/fixture/" + backupId + ".manifest.age", null, null);
                workers.start(request);
                // Restart after Docker owns the worker, before consuming its durable result.
                for (int i = 0; i < 200 && docker.inspect("pgcp-worker-" + backupId).isEmpty(); i++) Thread.sleep(50);
                workers.shutdown();
                Thread.sleep(200);
                workers = new LogicalWorkerService(props, docker, commands, locks, json, true, network);
                var saved = await(workers, workers.get(backupId));
                assertThat(workers.start(request).id()).isEqualTo(backupId);
                assertThat(saved.result()).containsKeys("sha256", "manifest", "artifactKey");
                assertThat(Files.exists(data.resolve("logical-operations/" + backupId + "/request.json"))).isFalse();
                assertThat(Files.readString(data.resolve("logical-operations/" + backupId + "/state.json")))
                        .doesNotContain(key.identity().trim(), "test-password");
                run(List.of("docker", "exec", "-u", "postgres", sourceId, "psql", "-X", "-d", "fixture", "-c",
                        "insert into orders(description) values ('after backup')"));
                UUID restoreId = UUID.randomUUID();
                instances = new InstanceManager(props, docker, commands, new HostService(props, docker), new InstanceCa(props),
                        new ImageService(props, docker, commands), json, cp);
                if (!docker.networkExists(AgentApi.INTERNAL_NETWORK)) run(List.of("docker", "network", "create", AgentApi.INTERNAL_NETWORK));
                instances.create(new InstanceSpec(restoredInstance, name, pgMajor, 512, 1, 128, Map.of(), null, null,
                        List.of(), "RestorePassword123456789", List.of(), restoreId), (p,m) -> {});
                String targetName = AgentApi.containerName(name);
                String targetId = docker.inspect(targetName).orElseThrow().id();
                run(List.of("docker", "exec", "-u", "postgres", targetName, "psql", "-X", "-c", "create role restored_owner login;"));
                run(List.of("docker", "exec", "-u", "postgres", targetName, "createdb", "-O", "restored_owner", "restored"));
                var restore = new LogicalBackupRequest(restoreId, LogicalBackupRequest.Action.RESTORE, restoredInstance,
                        targetName, targetId, pgMajor, new LogicalBackupRequest.Connection(targetName, 5432, "restored", "postgres",
                        "RestorePassword123456789", "require", null), storage, saved.result().get("artifactKey"),
                        saved.result().get("manifestKey"), saved.result().get("sha256"), "restored_owner");
                var result = await(workers, workers.start(restore));
                assertThat(result.result()).containsEntry("verified", "true").containsEntry("rowsChecked", "3");
                assertThat(run(List.of("docker", "exec", "-u", "postgres", targetName, "psql", "-X", "-d", "restored", "-Atc",
                        "select nextval('orders_id_seq')" )).trim()).isEqualTo("4");
                assertThat(run(List.of("docker", "exec", "-u", "postgres", sourceId, "psql", "-X", "-d", "fixture", "-Atc",
                        "select count(*) from orders")).trim()).isEqualTo("4");
                assertThat(docker.inspect(targetName).orElseThrow().networks()).containsExactly("pgcp-restore-" + restoreId);
                LogicalWorkerService current = workers;
                assertThatThrownBy(() -> current.start(new LogicalBackupRequest(UUID.randomUUID(), LogicalBackupRequest.Action.RESTORE,
                        request.instanceId(), source.name(), source.id(), pgMajor, connection, storage,
                        saved.result().get("artifactKey"), saved.result().get("manifestKey"), saved.result().get("sha256"), "restored_owner")))
                        .isInstanceOf(AgentException.class).hasMessageContaining("fresh isolated");
            } finally {
                if (workers != null) workers.shutdown();
                if (instances != null && docker.inspect(AgentApi.containerName(name)).isPresent()) {
                    instances.delete(name, restoredInstance, true, (p,m) -> {});
                }
                if (locks != null) locks.shutdown();
                compose("down", "--volumes", "--remove-orphans");
            }
        }
    }

    private Operation await(LogicalWorkerService service, Operation op) throws Exception {
        op=terminal(service,op);
        assertThat(op.status()).withFailMessage("%s: %s", op.type(), op.error()).isEqualTo(OperationStatus.SUCCEEDED);
        return op;
    }
    private Operation terminal(LogicalWorkerService service, Operation op) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        while (op.status() == OperationStatus.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(300);
            op = service.get(op.id());
        }
        return op;
    }
    private String compose(String... args) {
        var argv = new java.util.ArrayList<>(List.of("env", "PGCP_FIXTURE_MAJOR="+pgMajor, "docker", "compose", "-p", project, "-f", "../deploy/fixtures/logical-compose.yml"));
        argv.addAll(List.of(args));
        return run(argv);
    }
    private String run(List<String> args) { return commands.runChecked(args, null, Duration.ofMinutes(8)); }
}
