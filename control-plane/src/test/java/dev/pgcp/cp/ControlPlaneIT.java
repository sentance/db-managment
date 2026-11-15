package dev.pgcp.cp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.pgcp.agent.api.ContainerCredentials;
import dev.pgcp.agent.api.BackupCatalogue;
import dev.pgcp.agent.api.BackupInspectionRequest;
import dev.pgcp.agent.api.LogicalBackupRequest;
import dev.pgcp.agent.api.RecoveryKey;
import dev.pgcp.agent.api.Operation;
import dev.pgcp.agent.api.OperationType;
import dev.pgcp.agent.api.OperationStatus;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.agent.api.Libc;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.auth.TotpTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end through the HTTP API with a real metadata database and a real target PostgreSQL registered as an
 * external instance. The agent is not running here; agent-backed paths are covered by the agent's Docker tests and
 * the compose smoke test.
 */
@SpringBootTest(properties = "debug=false")
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ControlPlaneIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer meta = new PostgreSQLContainer("postgres:18-trixie");

    @Container
    static PostgreSQLContainer target = new PostgreSQLContainer("postgres:18-trixie").withUsername("target_admin")
            .withPassword("TargetSuperuserPassword4242");

    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        tmp = Files.createTempDirectory("pgcp-it");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path keyFile = tmp.resolve("master.key");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
        r.add("pgcp.crypto.master-key-file", keyFile::toString);
        r.add("pgcp.setup-token-file", () -> tmp.resolve("setup-token").toString());
        r.add("pgcp.agent.url", () -> "http://127.0.0.1:9");
        r.add("pgcp.agent.tls", () -> "false");
        r.add("pgcp.health-interval", () -> "1h");
        r.add("pgcp.backups.initial-delay", () -> "1h");
        r.add("pgcp.protection.initial-delay", () -> "1h");
        r.add("pgcp.notifications.initial-delay", () -> "1h");
        r.add("server.servlet.session.cookie.secure", () -> "false");
        for (String k : new String[] { "PGCP_AGENT_CLIENT_CERT", "PGCP_AGENT_CA" }) {
            r.add(k, () -> "classpath:tls/test-cert.pem");
        }
        r.add("PGCP_AGENT_CLIENT_KEY", () -> "classpath:tls/test-key.pem");
    }

    @MockitoBean
    AgentClient agent;

    @MockitoBean
    dev.pgcp.cp.notifications.TelegramTransport telegramTransport;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    dev.pgcp.cp.backups.BackupPolicyService policies;

    @Autowired dev.pgcp.cp.notifications.NotificationRepository notifications;
    @Autowired dev.pgcp.cp.notifications.TelegramService telegram;
    @Autowired dev.pgcp.cp.notifications.BackupAlertCoordinator alertCoordinator;
    @Autowired dev.pgcp.cp.backups.BackupPolicyRepository policyRepo;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbcClient;
    @Autowired dev.pgcp.cp.backups.LogicalRepository logicalRepo;
    @Autowired dev.pgcp.cp.backups.LogicalJobs.Restore restoreHandler;

    MockHttpSession session = new MockHttpSession();

    @Test
    void setupLoginManageExternalDatabaseAndAudit(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.setupRequired").value(true));
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/backups")).andExpect(status().isUnauthorized());

        // --- initial setup ---------------------------------------------------------------------------------------
        mvc.perform(post("/api/auth/setup").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("token", "wrong", "username", "admin", "password", "correct horse battery"))))
                .andExpect(status().isForbidden());
        String token = Files.readString(tmp.resolve("setup-token")).trim();
        JsonNode setup = call(post("/api/auth/setup"), Map.of("token", token, "username", "admin", "password",
                "correct horse battery"), 200);
        String totpSecret = setup.get("totpSecret").asString();
        MvcResult done = mvc.perform(post("/api/auth/setup/totp").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("code", TotpTestSupport.code(totpSecret, 0)))))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) done.getRequest().getSession();
        assertThat(json.readTree(done.getResponse().getContentAsString()).get("recoveryCodes").size()).isEqualTo(10);
        assertThat(Files.exists(tmp.resolve("setup-token"))).as("setup token is consumed").isFalse();
        call(get("/api/auth/status"), null, 200);

        // --- external instance in MANAGE_OBJECTS mode requires step-up --------------------------------------------
        Map<String, Object> external = Map.of("name", "target", "host", target.getHost(), "port", target.getFirstMappedPort(),
                "username", target.getUsername(), "password", target.getPassword(), "sslMode", "disable", "mode",
                "MANAGE_OBJECTS", "protection", "STANDARD");
        JsonNode denied = call(post("/api/instances/external"), external, 403);
        assertThat(denied.get("code").asString()).isEqualTo("STEP_UP_REQUIRED");
        String stepUpCode = TotpTestSupport.code(totpSecret, 1);
        call(post("/api/auth/step-up"), Map.of("code", stepUpCode), 200);
        String instanceId = call(post("/api/instances/external"), external, 200).get("id").asString();

        // A second registration in MONITOR_ONLY mode must refuse every change.
        String monitorId = call(post("/api/instances/external"), Map.of("name", "watch-only", "host", target.getHost(), "port",
                target.getFirstMappedPort(), "username", target.getUsername(), "password", target.getPassword(), "sslMode",
                "disable", "containerName", "backup-target"), 200).get("id").asString();
        call(post("/api/instances/" + monitorId + "/databases"), Map.of("name", "nope"), 403);
        assertThat(call(get("/api/instances/" + monitorId + "/monitoring"), null, 200).get("version").asString())
                .contains("PostgreSQL 18");

        // --- backup consent is independent of MONITOR_ONLY and cannot grant object/lifecycle access ---
        assertThat(call(get("/api/backups"), null, 200).toString()).contains("NOT_CONFIGURED", "UNKNOWN");
        String backupContainerId = "a".repeat(64);
        org.mockito.Mockito.when(agent.postgresContainers()).thenReturn(java.util.List.of(new ContainerInfo(
                backupContainerId, "backup-target", "postgres:18-trixie", "running", "healthy", "running", false, null,
                18, "18.4", Libc.GLIBC, java.util.List.of("coolify"), Map.of(), null)));
        Map<String, Object> binding = Map.of("containerName", "backup-target", "containerId", backupContainerId,
                "stanza", "shared", "staleAfterHours", 26);
        Object validStepUp = session.getAttribute("pgcp.stepUpUntil");
        session.removeAttribute("pgcp.stepUpUntil");
        call(put("/api/backups/sources/" + monitorId), binding, 403);
        session.setAttribute("pgcp.stepUpUntil", validStepUp);
        call(put("/api/backups/sources/" + instanceId), binding, 400);
        call(put("/api/backups/sources/" + monitorId), binding, 200);
        var catalogue = new BackupCatalogue("shared", 0,
                java.util.List.of(new BackupCatalogue.Repository(1, 0, "none")), java.util.List.of(),
                java.util.List.of(new BackupCatalogue.Backup("20261108-000021F", "full", Instant.now().minusSeconds(30),
                        Instant.now().minusSeconds(10), 1000, 300, false)));
        org.mockito.Mockito.when(agent.inspectBackups(new BackupInspectionRequest("backup-target", backupContainerId, "shared")))
                .thenReturn(catalogue);
        JsonNode backup = call(post("/api/backups/sources/" + monitorId + "/refresh"), null, 200);
        assertThat(backup.get("freshness").asString()).isEqualTo("FRESH");
        assertThat(backup.get("verificationState").asString()).isEqualTo("NEVER_VERIFIED");
        assertThat(backup.get("encryption").asString()).isEqualTo("UNENCRYPTED");
        org.mockito.Mockito.when(agent.inspectBackups(org.mockito.ArgumentMatchers.any()))
                .thenThrow(dev.pgcp.cp.common.ApiException.unavailable("secret-source-credential"));
        JsonNode unavailable = call(post("/api/backups/sources/" + monitorId + "/refresh"), null, 200);
        assertThat(unavailable.get("inspectionState").asString()).isEqualTo("UNREACHABLE");
        assertThat(unavailable.get("freshness").asString()).isEqualTo("UNKNOWN");
        assertThat(unavailable.toString()).contains("20261108-000021F").doesNotContain("secret-source-credential");
        org.mockito.Mockito.doThrow(dev.pgcp.cp.common.ApiException.conflict("source replaced"))
                .when(agent).inspectBackups(org.mockito.ArgumentMatchers.any());
        assertThat(call(post("/api/backups/sources/" + monitorId + "/refresh"), null, 200)
                .get("inspectionState").asString()).isEqualTo("BINDING_STALE");
        call(post("/api/instances/" + monitorId + "/databases"), Map.of("name", "still-nope"), 403);
        call(post("/api/backups/sources/" + monitorId + "/disconnect"), null, 204);

        // --- registration from a discovered container: credentials come from the agent and never leave the server ---
        String containerSecret = "ContainerEnvSecret987654";
        org.mockito.Mockito.when(agent.postgresContainers()).thenReturn(java.util.List.of(new ContainerInfo("c1", "unreachable-pg",
                "postgres:16-alpine", "running", "healthy", "running", false, null, 16, "16.14", Libc.MUSL,
                java.util.List.of("coolify"), Map.of("coolify.resourceName", "shared-db"), null)));
        org.mockito.Mockito.when(agent.postgresCredentials("unreachable-pg"))
                .thenReturn(new ContainerCredentials("unreachable-pg", "postgres", containerSecret, "postgres"));
        JsonNode unreachable = call(post("/api/instances/external/from-container"), Map.of("containerName", "unreachable-pg"), 400);
        assertThat(unreachable.toString()).contains("cannot connect").doesNotContain(containerSecret);
        call(post("/api/instances/external/from-container"), Map.of("containerName", "bad name!"), 400);
        call(post("/api/instances/external/from-container"), Map.of("containerName", "missing"), 404);

        // --- create a database with owner + read-only roles ------------------------------------------------------
        JsonNode created = call(post("/api/instances/" + instanceId + "/databases"), Map.of("name", "shop",
                "createReadOnlyRole", true), 202);
        String ownerPassword = created.get("credentials").get(0).get("password").asString();
        String roPassword = created.get("credentials").get(1).get("password").asString();
        assertThat(created.get("connection").get("endpoints").get(0).get("uri").asString()).contains(ownerPassword);
        awaitJob(created.get("jobId").asString());

        try (var c = DriverManager.getConnection(jdbc("shop"), "shop_owner", ownerPassword)) {
            c.createStatement().execute("create table orders(id int)");
        }
        try (var c = DriverManager.getConnection(jdbc("shop"), "shop_ro", roPassword)) {
            c.createStatement().executeQuery("select count(*) from orders").close();
        }

        // Duplicate names are rejected before anything runs.
        call(post("/api/instances/" + instanceId + "/databases"), Map.of("name", "shop"), 409);
        // Connection info is masked; reveal is allowed because step-up is still fresh.
        assertThat(call(get("/api/instances/" + instanceId + "/databases/shop/connection"), null, 200).toString())
                .doesNotContain(ownerPassword).contains("********");
        assertThat(call(post("/api/instances/" + instanceId + "/roles/shop_owner/reveal-password"), null, 200)
                .get("password").asString()).isEqualTo(ownerPassword);

        // Rotation invalidates the old password.
        String rotated = call(post("/api/instances/" + instanceId + "/roles/shop_ro/rotate-password"), null, 200)
                .get("password").asString();
        assertThat(rotated).isNotEqualTo(roPassword);
        DriverManager.getConnection(jdbc("shop"), "shop_ro", rotated).close();

        // Roles that pgcp uses or that are superusers are protected.
        call(post("/api/instances/" + instanceId + "/roles/" + target.getUsername() + "/rotate-password"), null, 403);

        // --- drop requires typing the name -------------------------------------------------------------------------
        call(post("/api/instances/" + instanceId + "/databases/shop/drop"), Map.of("confirmName", "wrong"), 400);
        String dropJob = call(post("/api/instances/" + instanceId + "/databases/shop/drop"), Map.of("confirmName", "shop",
                "terminateConnections", true), 202).get("jobId").asString();
        awaitJob(dropJob);
        assertThat(call(get("/api/instances/" + instanceId + "/databases"), null, 200).toString()).doesNotContain("\"shop\"");

        // --- encrypted storage setup and opt-in logical export; source remains MONITOR_ONLY -----------------------
        String storageSecret="StorageSecretMustRemainPrivate42";
        String recoveryIdentity="AGE-SECRET-KEY-1TESTRECOVERYIDENTITY42";
        org.mockito.Mockito.when(agent.recoveryKey()).thenReturn(new RecoveryKey("age1"+"q".repeat(58),recoveryIdentity));
        Map<String,Object> destinationRequest=Map.of("name","test storage","endpoint","https://s3.example.test","region","auto",
                "bucket","pgcp-fixture","accessKey","AccessKey42","secretKey",storageSecret);
        Object unlocked=session.getAttribute("pgcp.stepUpUntil");
        session.removeAttribute("pgcp.stepUpUntil");
        call(post("/api/backups/destinations"),destinationRequest,403);
        session.setAttribute("pgcp.stepUpUntil",unlocked);
        JsonNode destination=call(post("/api/backups/destinations"),destinationRequest,201);
        String destinationId=destination.get("destination").get("id").asString();
        assertThat(destination.get("recoveryIdentity").asString()).isEqualTo(recoveryIdentity);
        assertThat(call(get("/api/backups/destinations"),null,200).toString()).doesNotContain(storageSecret,recoveryIdentity,"credentialsSecretId","identitySecretId");
        org.mockito.Mockito.when(agent.postgresContainers()).thenReturn(java.util.List.of(new ContainerInfo(
                backupContainerId,"backup-target","postgres:18-trixie","running","healthy","running",false,null,
                18,"18.4",Libc.GLIBC,java.util.List.of("coolify"),Map.of(),null)));
        Map<String,Object> export=Map.of("database","postgres","destinationId",destinationId,"containerId",backupContainerId);
        call(post("/api/backups/logical/"+monitorId),export,400);
        org.mockito.Mockito.when(agent.logical(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{
            LogicalBackupRequest r=invocation.getArgument(0);
            Map<String,String> result=r.action()==LogicalBackupRequest.Action.TEST_STORAGE?Map.of("verified","true"):
                    Map.of("artifactKey",r.artifactKey(),"manifestKey",r.manifestKey(),"sha256","a".repeat(64),"sizeBytes","123","manifest","{}");
            return new Operation(r.operationId(),r.action()==LogicalBackupRequest.Action.TEST_STORAGE?OperationType.TEST_STORAGE:OperationType.LOGICAL_BACKUP,
                    "fixture",OperationStatus.SUCCEEDED,100,"done",null,Instant.now(),Instant.now(),result);
        });
        awaitJob(call(post("/api/backups/destinations/"+destinationId+"/test"),null,202).get("jobId").asString());
        JsonNode exportJob=call(post("/api/backups/logical/"+monitorId),export,202);
        awaitJob(exportJob.get("jobId").asString());
        String savedBackup=exportJob.get("id").asString();
        assertThat(call(get("/api/backups/logical"),null,200).toString()).contains("SUCCEEDED").doesNotContain(storageSecret,recoveryIdentity);
        call(post("/api/backups/logical/"+savedBackup+"/restore"),Map.of("instanceName","target","database","restored","owner","restored_owner"),409);
        call(post("/api/instances/"+monitorId+"/databases"),Map.of("name","still-forbidden"),403);

        // --- agent-owned policies: explicit consent, redacted snapshots and commit-before-ack replay ---
        var policyRequest=new java.util.HashMap<String,Object>(Map.of("name","Daily app","instanceId",monitorId,"database","postgres",
                "destinationId",destinationId,"containerId",backupContainerId,"dailyTime","02:00","timezone","Europe/Prague",
                "enabled",true,"runImmediately",false));
        policyRequest.put("maxAgeHours",26);
        session.removeAttribute("pgcp.stepUpUntil");
        call(post("/api/backups/policies"),policyRequest,403);
        session.setAttribute("pgcp.stepUpUntil",unlocked);
        policyRequest.put("timezone","invalid-zone");call(post("/api/backups/policies"),policyRequest,400);
        policyRequest.put("timezone","Europe/Prague");
        var policy=call(post("/api/backups/policies"),policyRequest,201);
        String policyId=policy.get("id").asString();
        assertThat(policy.get("syncState").asString()).isEqualTo("PENDING");
        var observed=new java.util.HashMap<java.util.UUID,dev.pgcp.agent.api.BackupPolicyStatus>();
        org.mockito.Mockito.when(agent.backupPolicies()).thenAnswer(i->java.util.List.copyOf(observed.values()));
        org.mockito.Mockito.when(agent.backupEvents()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(agent.backupPolicy(org.mockito.ArgumentMatchers.any())).thenAnswer(i->{
            dev.pgcp.agent.api.BackupPolicy p=i.getArgument(0);
            assertThat(p.template().storage().identity()).isNull();
            var s=new dev.pgcp.agent.api.BackupPolicyStatus(p.id(),p.revision(),p.enabled(),Instant.now().plusSeconds(3600),null,
                    null,null,null,p.enabled()?"AWAITING_FIRST_BACKUP":"PAUSED");observed.put(p.id(),s);return s;
        });
        policies.reconcile();
        assertThat(call(get("/api/backups/policies"),null,200).toString()).contains("APPLIED","AWAITING_FIRST_BACKUP")
                .doesNotContain(storageSecret,recoveryIdentity,target.getPassword(),"templateSecretId","template");
        java.util.UUID runId=java.util.UUID.randomUUID(),eventId=java.util.UUID.randomUUID();
        var event=new dev.pgcp.agent.api.BackupPolicyEvent(eventId,java.util.UUID.fromString(policyId),1,runId,
                java.util.UUID.fromString(monitorId),java.util.UUID.fromString(destinationId),"postgres",backupContainerId,18,
                "FAILED",Map.of(),Instant.now(),Instant.now());
        org.mockito.Mockito.when(agent.backupEvents()).thenReturn(java.util.List.of(event));
        org.mockito.Mockito.doThrow(dev.pgcp.cp.common.ApiException.unavailable("lost acknowledgement"))
                .doNothing().when(agent).acknowledgeBackupEvent(eventId);
        policies.reconcile();policies.reconcile();
        JsonNode history=call(get("/api/backups/logical"),null,200);
        long occurrences=java.util.stream.StreamSupport.stream(history.spliterator(),false).filter(n->n.get("id").asString().equals(runId.toString())).count();
        assertThat(occurrences).isEqualTo(1);
        var pendingPause=call(put("/api/backups/policies/"+policyId+"/enabled"),Map.of("enabled",false),200);
        assertThat(pendingPause.get("enabled").asBoolean()).isFalse();
        assertThat(pendingPause.get("execution").get("enabled").asBoolean()).isTrue();
        assertThat(pendingPause.get("health").asString()).isEqualTo("SYNC_PENDING");
        policies.reconcile();
        assertThat(call(get("/api/backups/policies"),null,200).toString()).contains("PAUSED");
        org.mockito.Mockito.when(agent.backupPolicies()).thenThrow(dev.pgcp.cp.common.ApiException.unavailable("secret-agent-diagnostic"));
        policies.reconcile();
        assertThat(call(get("/api/backups/policies"),null,200).toString()).contains("UNKNOWN","UNCONFIRMED")
                .doesNotContain("secret-agent-diagnostic");

        // Expiration results can arrive before the original completion, without resurrecting deleted artifacts.
        java.util.UUID expiredId=java.util.UUID.randomUUID();
        for(String state:java.util.List.of("EXPIRED","SUCCEEDED","EXPIRING")) {
            policyRepo.ingest(new dev.pgcp.agent.api.BackupPolicyEvent(java.util.UUID.randomUUID(),java.util.UUID.fromString(policyId),1,expiredId,
                    java.util.UUID.fromString(monitorId),java.util.UUID.fromString(destinationId),"postgres",backupContainerId,18,state,
                    Map.of("sha256","a".repeat(64)),Instant.now(),Instant.now()));
        }
        assertThat(jdbcClient.sql("select status from logical_backup where id=?").param(expiredId).query(String.class).single()).isEqualTo("EXPIRED");
        call(post("/api/backups/logical/"+expiredId+"/restore"),Map.of("instanceName","expired-target","database","restored","owner","restored_owner"),400);
        org.mockito.Mockito.doThrow(dev.pgcp.cp.common.ApiException.conflict("backup expiration has started"))
                .when(agent).pinBackup(org.mockito.ArgumentMatchers.eq(java.util.UUID.fromString(savedBackup)),org.mockito.ArgumentMatchers.any());
        call(post("/api/backups/logical/"+savedBackup+"/restore"),Map.of("instanceName","pin-race-target","database","restored","owner","restored_owner"),409);
        assertThat(jdbcClient.sql("select count(*) from logical_restore where target_name='pin-race-target'").query(Integer.class).single()).isZero();
        var failedRestore=java.util.UUID.randomUUID();
        logicalRepo.insertRestore(failedRestore,java.util.UUID.fromString(savedBackup),"failed-fixture-restore","restored","restored_owner");
        var failedContext=org.mockito.Mockito.mock(dev.pgcp.cp.jobs.JobContext.class);
        org.mockito.Mockito.when(failedContext.params(dev.pgcp.cp.backups.LogicalBackupService.ItemParams.class))
                .thenReturn(new dev.pgcp.cp.backups.LogicalBackupService.ItemParams(failedRestore));
        restoreHandler.onFailure(failedContext,new IllegalStateException("private-restore-diagnostic"));
        alertCoordinator.reconcile();
        assertThat(notifications.alerts().stream().anyMatch(a->a.kind().equals("RESTORE_FAILED")&&a.active())).isTrue();
        var laterRestore=java.util.UUID.randomUUID();
        logicalRepo.insertRestore(laterRestore,java.util.UUID.fromString(savedBackup),"verified-fixture-restore","restored","restored_owner");
        logicalRepo.restoreStatus(laterRestore,"SUCCEEDED",Map.of("verified","true"));alertCoordinator.reconcile();
        assertThat(notifications.alerts().stream().anyMatch(a->a.kind().equals("RESTORE_FAILED")&&!a.active())).isTrue();

        // Telegram configuration remains encrypted/redacted; delivery retries preserve alert/recovery ordering.
        String botToken="123456789:TelegramPrivateTokenMustNeverAppear42";
        var telegramRequest=Map.of("enabled",true,"chatId","-100123456789","token",botToken);
        session.removeAttribute("pgcp.stepUpUntil");call(put("/api/notifications/telegram"),telegramRequest,403);
        session.setAttribute("pgcp.stepUpUntil",unlocked);
        assertThat(call(put("/api/notifications/telegram"),telegramRequest,200).toString()).contains("configured").doesNotContain(botToken,"tokenSecretId");
        assertThat(call(get("/api/notifications/telegram"),null,200).toString()).doesNotContain(botToken);
        alertCoordinator.reconcile();alertCoordinator.reconcile();
        assertThat(notifications.alerts().stream().filter(a->a.kind().equals("AGENT_UNREACHABLE")&&a.active()).count()).isEqualTo(1);
        // Leave the policy alert journal, but isolate delivery ordering below.
        jdbcClient.sql("update notification_delivery set status='CANCELLED'").update();
        var resource=java.util.UUID.randomUUID();
        notifications.condition("test-retry","BACKUP_FAILED",resource,true,"Scheduled backup failed","Backup succeeded");
        notifications.condition("test-retry","BACKUP_FAILED",resource,true,"Scheduled backup failed","Backup succeeded");
        notifications.condition("test-retry","BACKUP_FAILED",resource,false,"Scheduled backup failed","Backup succeeded");
        assertThat(notifications.deliveries().stream().filter(d->d.status().equals("PENDING")).count()).isEqualTo(2);
        org.mockito.Mockito.when(telegramTransport.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new dev.pgcp.cp.notifications.TelegramTransport.Outcome(false,120),new dev.pgcp.cp.notifications.TelegramTransport.Outcome(true,0));
        telegram.deliver();
        org.mockito.Mockito.verify(telegramTransport,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.eq(botToken),org.mockito.ArgumentMatchers.eq("-100123456789"),org.mockito.ArgumentMatchers.anyString());
        var retry=notifications.deliveries().stream().filter(d->d.attempts()==1).findFirst().orElseThrow();
        assertThat(retry.status()).isEqualTo("PENDING");assertThat(retry.nextAttemptAt()).isAfter(Instant.now().plusSeconds(110));
        jdbcClient.sql("update notification_delivery set next_attempt_at=now() where status='PENDING'").update();
        telegram.deliver();assertThat(notifications.deliveries().stream().filter(d->d.status().equals("SENT")).count()).isEqualTo(2);
        notifications.condition("test-restart","STALE",resource,true,"Backup is stale","Fresh backup received");
        var claimed=notifications.claim(Instant.now()).orElseThrow();
        jdbcClient.sql("update notification_delivery set claimed_at=now()-interval '3 minutes' where id=?").param(claimed.id()).update();
        telegram.deliver();
        assertThat(notifications.deliveries().stream().filter(d->d.id().equals(claimed.id())).findFirst().orElseThrow().attempts()).isEqualTo(2);
        notifications.condition("test-exhaust","STALE",resource,true,"Backup is stale","Fresh backup received");
        org.mockito.Mockito.doReturn(new dev.pgcp.cp.notifications.TelegramTransport.Outcome(false,0)).when(telegramTransport)
                .send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        for(int attempt=0;attempt<8;attempt++) {
            jdbcClient.sql("update notification_delivery set next_attempt_at=now() where status='PENDING'").update();telegram.deliver();
        }
        assertThat(notifications.deliveries().stream().anyMatch(d->d.status().equals("FAILED")&&d.attempts()==8)).isTrue();
        notifications.condition("test-disable","STALE",resource,true,"Backup is stale","Fresh backup received");
        call(put("/api/notifications/telegram"),Map.of("enabled",true,"chatId","-100123456789","token",""),200);
        assertThat(notifications.deliveries().stream().filter(d->d.status().equals("PENDING")).count()).isEqualTo(1);
        call(put("/api/notifications/telegram"),Map.of("enabled",false,"chatId","-100123456789","token",""),200);
        assertThat(notifications.deliveries().stream().filter(d->d.status().equals("PENDING")).count()).isZero();
        assertThat(call(get("/api/notifications/deliveries"),null,200).toString()).doesNotContain(botToken);

        // --- audit chain is intact and never contains secrets --------------------------------------------------------
        assertThat(call(get("/api/audit/verify"), null, 200).get("intact").asBoolean()).isTrue();
        String audit = call(get("/api/audit?limit=500"), null, 200).toString();
        assertThat(audit).contains("database.create", "role.reveal_password", "auth.step_up")
                .doesNotContain(ownerPassword, roPassword, rotated, target.getPassword(), containerSecret);
        assertThat(output.getAll()).doesNotContain(ownerPassword, roPassword, rotated, target.getPassword(), totpSecret, token,
                containerSecret,storageSecret,recoveryIdentity,botToken)
                .doesNotContain("generated security password");

        // --- recovery codes can be regenerated (step-up still fresh); the OpenAPI document is published to docs/ ---
        assertThat(call(post("/api/auth/recovery-codes"), null, 200).get("recoveryCodes").size()).isEqualTo(10);
        String openApi = mvc.perform(get("/api/docs").session(session)).andReturn().getResponse().getContentAsString();
        assertThat(openApi).contains("/api/instances/managed");
        assertThat(json.readTree(openApi).at("/components/schemas/PolicyView/properties/execution/$ref").asString())
                .isEqualTo("#/components/schemas/BackupPolicyStatus");
        assertThat(json.readTree(openApi).at("/components/schemas/View/properties/backupState").isMissingNode()).isFalse();
        Path docs = Path.of("..", "docs");
        if (Files.isDirectory(docs)) {
            Files.writeString(docs.resolve("openapi.json"), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(json.readTree(openApi)) + "\n");
        }

        // --- replaying an already used TOTP code is rejected (as a replay, or as expired if a minute has passed) ------
        call(post("/api/auth/logout"), null, 204);
        session = new MockHttpSession();
        call(post("/api/auth/login"), Map.of("username", "admin", "password", "correct horse battery", "code", stepUpCode),
                401);
    }

    private void awaitJob(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode job = call(get("/api/jobs/" + jobId), null, 200).get("job");
            String s = job.get("status").asString();
            if (s.equals("SUCCEEDED")) {
                return;
            }
            assertThat(s).as("job error: " + job.get("error")).isNotEqualTo("FAILED");
            Thread.sleep(300);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body,
            int expectedStatus) throws Exception {
        req = req.with(csrf()).session(session);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body(body));
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        String content = r.getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private String body(Object o) {
        return json.writeValueAsString(o);
    }

    private static String jdbc(String db) {
        return "jdbc:postgresql://" + target.getHost() + ":" + target.getFirstMappedPort() + "/" + db;
    }
}
